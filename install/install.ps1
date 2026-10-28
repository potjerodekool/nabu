<#
.SYNOPSIS
    Installs Nabu from the GitHub releases.

.DESCRIPTION
    irm https://raw.githubusercontent.com/potjerodekool/nabu/main/install/install.ps1 | iex
    & ([scriptblock]::Create((irm https://raw.githubusercontent.com/potjerodekool/nabu/main/install/install.ps1))) -Native

    The script changes nothing outside the installation directory unless you confirm it (or give -ModifyPath).
    Environment: NABU_HOME, NABU_VERSION, NABU_RELEASES_URL (the releases page, for a mirror or a test).

.PARAMETER Version
    Install this version instead of the latest.

.PARAMETER Native
    Also install the native backend for this platform.

.PARAMETER NabuHome
    Install in this directory (default: $env:NABU_HOME or %USERPROFILE%\.nabu).

.PARAMETER ModifyPath
    Add the bin directory to your user PATH without asking.

.PARAMETER NoModifyPath
    Never touch your PATH; the script says what to do yourself.
#>
[CmdletBinding()]
param(
    [string] $Version = $env:NABU_VERSION,
    [switch] $Native,
    [string] $NabuHome = $env:NABU_HOME,
    [switch] $ModifyPath,
    [switch] $NoModifyPath
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
try {
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
} catch { }

$base = if ($env:NABU_RELEASES_URL) { $env:NABU_RELEASES_URL.TrimEnd('/') } else { 'https://github.com/potjerodekool/nabu/releases' }
if (-not $NabuHome) { $NabuHome = Join-Path $env:USERPROFILE '.nabu' }

function Fail([string] $message) {
    Write-Host "install: $message" -ForegroundColor Red
    exit 1
}

# --- the platform
$arch = if ($env:PROCESSOR_ARCHITEW6432) { $env:PROCESSOR_ARCHITEW6432 } else { $env:PROCESSOR_ARCHITECTURE }
switch ($arch) {
    'AMD64' { $platform = 'windows-x86_64' }
    'ARM64' { $platform = 'windows-arm64' }
    default { Fail "unsupported processor: $arch" }
}

# --- the version
if (-not $Version) {
    $request = [Net.HttpWebRequest]::Create("$base/latest")
    $request.AllowAutoRedirect = $false
    try {
        $response = $request.GetResponse()
        $location = $response.Headers['Location']
        $response.Close()
    } catch {
        Fail "could not find the latest release at $base/latest ($($_.Exception.Message))"
    }
    if (-not $location -or $location -notmatch '/tag/(.+)$') { Fail "could not find the latest release at $base/latest" }
    $Version = $Matches[1]
}
$Version = $Version.TrimStart('v')
$tag = "v$Version"
Write-Host "Installing nabu $Version ($platform) in $NabuHome"

$tmp = Join-Path ([IO.Path]::GetTempPath()) ("nabu-install-" + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $tmp | Out-Null
try {
    function Get-Release([string] $file) {
        Write-Host "  downloading $file"
        try {
            Invoke-WebRequest -UseBasicParsing -Uri "$base/download/$tag/$file" -OutFile (Join-Path $tmp $file)
        } catch {
            Fail "could not download $base/download/$tag/${file}: $($_.Exception.Message)"
        }
    }
    function Test-Release([string] $file) {
        $line = Get-Content (Join-Path $tmp 'SHA256SUMS') | Where-Object { $_ -match "^([0-9a-fA-F]{64})\s+\*?$([regex]::Escape($file))\s*$" } | Select-Object -First 1
        if (-not $line) { Fail "$file is not in SHA256SUMS of release $tag (is it published for $platform?)" }
        $wanted = ([regex]::Match($line, '^[0-9a-fA-F]{64}')).Value.ToLower()
        $actual = (Get-FileHash -Algorithm SHA256 -Path (Join-Path $tmp $file)).Hash.ToLower()
        if ($actual -ne $wanted) { Fail "$file does not match SHA256SUMS; nothing was installed" }
    }

    $core = "nabu-$Version.zip"
    $nativeFile = "nabu-native-$Version-$platform.zip"
    Get-Release 'SHA256SUMS'
    Get-Release $core
    Test-Release $core
    if ($Native) {
        Get-Release $nativeFile
        Test-Release $nativeFile
    }

    # --- install: everything is downloaded and checked
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $target = Join-Path $NabuHome "versions\$Version"
    $partial = "$target.partial"
    if (Test-Path $partial) { Remove-Item -Recurse -Force $partial }
    New-Item -ItemType Directory -Force -Path (Join-Path $NabuHome 'bin') | Out-Null
    [IO.Compression.ZipFile]::ExtractToDirectory((Join-Path $tmp $core), $partial)
    if ($Native) { [IO.Compression.ZipFile]::ExtractToDirectory((Join-Path $tmp $nativeFile), $partial) }
    if (Test-Path $target) { Remove-Item -Recurse -Force $target }
    Move-Item $partial $target
    Copy-Item (Join-Path $target 'bin\nabu.cmd') (Join-Path $NabuHome 'bin\nabu.cmd') -Force
    Copy-Item (Join-Path $target 'bin\nabu') (Join-Path $NabuHome 'bin\nabu') -Force
    [IO.File]::WriteAllText((Join-Path $NabuHome 'current.new'), "$Version`n")
    Move-Item (Join-Path $NabuHome 'current.new') (Join-Path $NabuHome 'current') -Force
    Write-Host "nabu $Version is installed in $NabuHome"
} finally {
    Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
}

# --- Java
$javaFound = $false
foreach ($candidate in @($env:NABU_JAVA_HOME, $env:JAVA_HOME)) {
    if ($candidate -and (Test-Path (Join-Path $candidate 'bin\java.exe'))) { $javaFound = $true }
}
if (-not $javaFound -and (Get-Command java -ErrorAction SilentlyContinue)) { $javaFound = $true }
if (-not $javaFound) {
    Write-Host 'note: no Java found. Nabu needs a JDK 21 or later: install one, or set NABU_JAVA_HOME or JAVA_HOME.'
}

# --- the PATH (the user's PATH, not the machine's)
$bin = Join-Path $NabuHome 'bin'
$userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
$onPath = $false
foreach ($entry in (($userPath, $env:Path) -join ';').Split(';')) {
    if ($entry -and $entry.TrimEnd('\') -ieq $bin.TrimEnd('\')) { $onPath = $true }
}
if (-not $onPath) {
    $answer = $false
    if ($ModifyPath) {
        $answer = $true
    } elseif (-not $NoModifyPath -and [Environment]::UserInteractive -and -not [Console]::IsInputRedirected) {
        $reply = Read-Host "Add $bin to your user PATH? [y/N]"
        $answer = $reply -match '^(y|yes)$'
    }
    if ($answer) {
        $newPath = if ($userPath) { "$userPath;$bin" } else { $bin }
        [Environment]::SetEnvironmentVariable('Path', $newPath, 'User')
        Write-Host "Added $bin to your user PATH. Open a new terminal to use nabu."
    } else {
        Write-Host ''
        Write-Host "$bin is not on your PATH. To use nabu, add it yourself:"
        Write-Host "    [Environment]::SetEnvironmentVariable('Path', [Environment]::GetEnvironmentVariable('Path', 'User') + ';$bin', 'User')"
        Write-Host '(or: Settings > System > About > Advanced system settings > Environment Variables)'
        Write-Host "or run nabu with its full path: $bin\nabu.cmd"
    }
}

Write-Host ''
Write-Host 'Next: nabu doctor     (checks what is needed on this machine)'
if ($Native) {
    # a program that writes to stderr is an error in Windows PowerShell 5.1 with ErrorActionPreference Stop
    $ErrorActionPreference = 'Continue'
    & (Join-Path $bin 'nabu.cmd') doctor 2>&1 | ForEach-Object { "$_" } | Write-Host
    if ($LASTEXITCODE -ne 0) { Write-Host '(nabu doctor lists what is missing for the native backend)' }
} else {
    Write-Host '      nabu self-update --native     (adds the native backend)'
}

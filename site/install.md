---
layout: page
title: Installing Nabu
---

<div class="callout callout-info" markdown="1">
**Only a pre-release exists so far: `v0.1.0-rc1`.** GitHub does not count a pre-release as the "latest" release, so the commands below with `/releases/latest` work from the first stable release on. Until then give the version: `--version 0.1.0-rc1` (`-Version 0.1.0-rc1` on Windows), with the URL `https://github.com/potjerodekool/nabu/releases/download/v0.1.0-rc1/install.sh` (or `install.ps1`). The release has been built and tried on GitHub Actions for Windows x64 and Linux x64, and installed from GitHub on Windows x64. It has not been tried on macOS or Linux arm64.
</div>

Nabu comes in two parts that you install separately:

| Part | What it is | Size | Platforms |
|---|---|---|---|
| **Core** | the `nabu` command, the compiler, the language support and the JVM backend | about 18 MB | one file for every platform |
| **Native backend** | the LLVM backend, with the LLVM libraries of its platform | about 470 to 590 MB | one file per platform and processor |

If you build for the JVM you only need the core (and a Maven build gets it from Maven, without this page). The native backend is for building native executables; see [nabu-native-image](/backends/llvm/native-image/) and [Installing the LLVM toolchain](/backends/llvm/installation/).

## Install

You need a **JDK 21 or later**. For the native backend you also need a C compiler and the Boehm GC, see [Requirements](#requirements-for-the-native-backend).

**Linux and macOS**

```
curl -fsSL https://github.com/potjerodekool/nabu/releases/latest/download/install.sh | sh
curl -fsSL https://github.com/potjerodekool/nabu/releases/latest/download/install.sh | sh -s -- --native
```

**Windows (PowerShell)**

```
iex (New-Object Net.WebClient).DownloadString('https://github.com/potjerodekool/nabu/releases/latest/download/install.ps1')
& ([scriptblock]::Create((New-Object Net.WebClient).DownloadString('https://github.com/potjerodekool/nabu/releases/latest/download/install.ps1'))) -Native
```

(`irm` does not work here: GitHub serves release files as binary, and `irm` then returns bytes instead of text.)

The second line of each pair also installs the native backend. The scripts download the files of the latest release, check each one against `SHA256SUMS` and only then install; a file that does not match installs nothing.

| Option (`install.sh`) | Option (`install.ps1`) | |
|---|---|---|
| `--version <v>` | `-Version <v>` | install this version instead of the latest |
| `--native` | `-Native` | also install the native backend |
| `--home <dir>` | `-NabuHome <dir>` | install here (default `~/.nabu` or `%USERPROFILE%\.nabu`; also `NABU_HOME`) |
| `--modify-path` | `-ModifyPath` | add the `bin` directory to your PATH without asking |
| `--no-modify-path` | `-NoModifyPath` | never touch your PATH |

### "File is not digitally signed" (Windows)

If you download `install.ps1` first and run it, PowerShell can refuse with *"cannot be loaded. The file is not digitally signed"*. A file from the internet is marked as such, and the execution policy of many machines (`RemoteSigned`, `AllSigned`) then asks for a signature. Nabu does not sign the script. You can:

- run the script from the command above, which downloads the text and runs it without a file on disk, so the policy does not apply; or
- remove the mark and run the file: `Unblock-File .\install.ps1`, then `.\install.ps1`; or
- when the policy is `AllSigned`, run it once with the policy bypassed, for this one process only: `powershell -ExecutionPolicy Bypass -File .\install.ps1`.

Changing the execution policy of your machine for good is not needed.

### Your PATH

The script **changes nothing outside the installation directory unless you say so**. When `bin` is not on your PATH it asks, in a terminal, whether it may add it: on Linux and macOS by appending one line to your shell profile (`~/.bashrc`, `~/.zshrc`, `~/.config/fish/config.fish` or `~/.profile`), on Windows by adding the directory to your *user* PATH. The default answer is no. Without a terminal (a pipeline, a container) it does not ask and does not change anything.

If you do not want the script to do it, it tells you what to do yourself:

```
export PATH="$HOME/.nabu/bin:$PATH"          # in your shell profile (Linux and macOS)
```

```
[Environment]::SetEnvironmentVariable('Path', [Environment]::GetEnvironmentVariable('Path', 'User') + ';' + "$env:USERPROFILE\.nabu\bin", 'User')
```

(or through *Settings, System, About, Advanced system settings, Environment Variables*). You can always run `~/.nabu/bin/nabu` by its full path.

## Requirements for the native backend

`nabu doctor` checks all of this and says what is missing.

| Platform | You need |
|---|---|
| Linux x64 | `gcc` and the Boehm GC (Debian and Ubuntu: `sudo apt install build-essential libgc-dev`) |
| Windows x64 | MinGW-w64 `gcc` with `libgc`, and the [Microsoft Visual C++ Redistributable](https://aka.ms/vs/17/release/vc_redist.x64.exe) |
| macOS | `clang` (`xcode-select --install`) and the Boehm GC (`brew install bdw-gc`); not tested |

Nabu does not ship the Microsoft runtime that its LLVM libraries need on Windows: Microsoft's terms for redistributing it are not those of Nabu. Many machines have it already; `nabu doctor` tells you if yours does not.

## Check and use

```
nabu version
nabu doctor
nabu native-image -jar app.jar          # needs the native backend
nabu-native-image -jar app.jar          # the same, as a command of its own (also needs the native backend)
```

## Update

```
nabu self-update --check       # is there a newer release?
nabu self-update               # install the latest release and switch to it
nabu self-update --native      # also add the native backend
nabu self-update --version 0.2.0
```

`self-update` installs the new version next to the old one (`~/.nabu/versions/<version>`), checks every file against `SHA256SUMS` before it installs anything, and then switches by changing the file `~/.nabu/current`. If anything fails nothing is changed. The previous version stays, so `nabu self-update --version <old>` goes back. If the version you run has the native backend, the new one gets it too (`--no-native` leaves it out).

## In a pipeline (CI)

Run the install script with a fixed version, and put `bin` on the PATH of the job yourself (the script does not touch a profile without a terminal). On GitHub Actions:

```yaml
- uses: actions/setup-java@v5
  with: { distribution: temurin, java-version: '21' }
- name: Cache Nabu
  uses: actions/cache@v4
  with:
    path: ~/.nabu
    key: nabu-${{ runner.os }}-0.2.0
- name: Install Nabu
  run: |
    curl -fsSL https://github.com/potjerodekool/nabu/releases/latest/download/install.sh | sh -s -- --version 0.2.0 --native --no-modify-path
    echo "$HOME/.nabu/bin" >> "$GITHUB_PATH"
- run: nabu doctor
```

`ubuntu-latest` has `gcc`; add `sudo apt-get install -y libgc-dev` for the Boehm GC. The exit code of `nabu doctor` is 1 when something that is needed is missing.

## Where things are

```
~/.nabu/
  bin/nabu, bin/nabu.cmd     the launcher: it does not change when Nabu is updated
  bin/nabu-native-image(.cmd)  the same for the native backend; only there when the backend is installed
  current                    the version that the launcher starts
  versions/<version>/        the jars of one version (lib), the licenses
```

To remove Nabu, delete `~/.nabu` and the PATH line you added.

## The release files

A release has `nabu-<version>.zip` (core), `nabu-native-<version>-<platform>.zip` for each platform (`linux-x86_64`, `windows-x86_64`), `SHA256SUMS`, `install.sh` and `install.ps1`. What the executables you build contain, and under which licenses, is in [Licenses](/backends/llvm/native-image/#licenses) and in the files `NOTICE`, `THIRD-PARTY-NOTICES.md` and `licenses/` of the core archive.

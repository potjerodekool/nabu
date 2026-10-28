#!/bin/sh
# Installs Nabu from the GitHub releases: curl -fsSL https://raw.githubusercontent.com/potjerodekool/nabu/main/install/install.sh | sh
#
# Options:
#   --version <v>      install this version instead of the latest
#   --native           also install the native backend for this platform
#   --home <dir>       install in this directory (default: $NABU_HOME or ~/.nabu)
#   --modify-path      add <home>/bin to the PATH in your shell profile without asking
#   --no-modify-path   never touch your shell profile; the script says what to do yourself
#   -h, --help         this text
#
# The script changes nothing outside <home> unless you confirm it (or give --modify-path).
# Environment: NABU_HOME, NABU_VERSION, NABU_RELEASES_URL (the releases page, for a mirror or a test).
set -eu

base="${NABU_RELEASES_URL:-https://github.com/potjerodekool/nabu/releases}"
home="${NABU_HOME:-$HOME/.nabu}"
version="${NABU_VERSION:-}"
native=no
modify=ask

say() { printf '%s\n' "$*"; }
fail() { printf 'install: %s\n' "$*" >&2; exit 1; }

usage() {
    cat <<'EOF'
Usage: install.sh [options]

  --version <v>      install this version instead of the latest
  --native           also install the native backend for this platform
  --home <dir>       install in this directory (default: $NABU_HOME or ~/.nabu)
  --modify-path      add <home>/bin to the PATH in your shell profile without asking
  --no-modify-path   never touch your shell profile; the script says what to do yourself
  -h, --help         this text

The script changes nothing outside <home> unless you confirm it (or give --modify-path).
Environment: NABU_HOME, NABU_VERSION, NABU_RELEASES_URL (the releases page, for a mirror or a test).
EOF
}

while [ $# -gt 0 ]; do
    case "$1" in
        --version) [ $# -ge 2 ] || fail "--version needs a version"; version="$2"; shift ;;
        --native) native=yes ;;
        --home) [ $# -ge 2 ] || fail "--home needs a directory"; home="$2"; shift ;;
        --modify-path) modify=yes ;;
        --no-modify-path) modify=no ;;
        -h|--help) usage; exit 0 ;;
        *) fail "unknown option: $1 (see --help)" ;;
    esac
    shift
done

# --- the platform
os=$(uname -s)
arch=$(uname -m)
case "$os" in
    Linux) system=linux ;;
    Darwin) system=macosx ;;
    MINGW*|MSYS*|CYGWIN*) fail "this is Windows: use install.ps1 (PowerShell) instead" ;;
    *) fail "unsupported system: $os" ;;
esac
case "$arch" in
    x86_64|amd64) cpu=x86_64 ;;
    aarch64|arm64) cpu=arm64 ;;
    *) fail "unsupported processor: $arch" ;;
esac
platform="$system-$cpu"

# --- what is needed
if command -v curl >/dev/null 2>&1; then
    fetch() { curl -fsSL -o "$2" "$1"; }
    redirect() { curl -s -o /dev/null -w '%{redirect_url}' "$1"; }
elif command -v wget >/dev/null 2>&1; then
    fetch() { wget -q -O "$2" "$1"; }
    redirect() { wget -S --max-redirect=0 -O /dev/null "$1" 2>&1 | sed -n 's/^ *[Ll]ocation: *//p' | tr -d '\r' | head -n 1; }
else
    fail "curl or wget is needed to download Nabu"
fi

if command -v sha256sum >/dev/null 2>&1; then
    sha256() { sha256sum "$1" | cut -d ' ' -f 1; }
elif command -v shasum >/dev/null 2>&1; then
    sha256() { shasum -a 256 "$1" | cut -d ' ' -f 1; }
else
    fail "sha256sum or shasum is needed to check the download"
fi

if [ -n "${NABU_JAVA_HOME:-}" ]; then java="$NABU_JAVA_HOME/bin/java"; elif [ -n "${JAVA_HOME:-}" ]; then java="$JAVA_HOME/bin/java"; else java=java; fi
unpack() {
    if command -v unzip >/dev/null 2>&1; then
        unzip -q -o "$1" -d "$2"
        return
    fi
    jar=jar
    case "$java" in
        */java) if [ -x "${java%/*}/jar" ]; then jar="${java%/*}/jar"; fi ;;
    esac
    command -v "$jar" >/dev/null 2>&1 || fail "unzip (or the jar command of a JDK) is needed to unpack Nabu"
    (cd "$2" && "$jar" xf "$1")
}

# --- the version
if [ -z "$version" ]; then
    location=$(redirect "$base/latest") || true
    case "$location" in
        */tag/*) tag=${location##*/tag/} ;;
        *) fail "could not find the latest release at $base/latest" ;;
    esac
    version=${tag#v}
fi
version=${version#v}
tag="v$version"
say "Installing nabu $version ($platform) in $home"

tmp=$(mktemp -d "${TMPDIR:-/tmp}/nabu-install.XXXXXX")
trap 'rm -rf "$tmp"' EXIT INT TERM

get() {
    say "  downloading $1"
    fetch "$base/download/$tag/$1" "$tmp/$1" || fail "could not download $base/download/$tag/$1"
}
check() {
    wanted=$(sed -n "s/^\([0-9a-fA-F]\{64\}\)  *\*\{0,1\}$1\$/\1/p" "$tmp/SHA256SUMS" | head -n 1 | tr 'A-F' 'a-f')
    [ -n "$wanted" ] || fail "$1 is not in SHA256SUMS of release $tag (is it published for $platform?)"
    [ "$(sha256 "$tmp/$1")" = "$wanted" ] || fail "$1 does not match SHA256SUMS; nothing was installed"
}

core="nabu-$version.zip"
nativefile="nabu-native-$version-$platform.zip"
get SHA256SUMS
get "$core"
check "$core"
if [ "$native" = yes ]; then
    get "$nativefile"
    check "$nativefile"
fi

# --- install: everything is downloaded and checked
target="$home/versions/$version"
rm -rf "$target.partial"
mkdir -p "$target.partial" "$home/bin"
unpack "$tmp/$core" "$target.partial"
if [ "$native" = yes ]; then unpack "$tmp/$nativefile" "$target.partial"; fi
rm -rf "$target"
mv "$target.partial" "$target"
cp "$target/bin/nabu" "$home/bin/nabu"
cp "$target/bin/nabu.cmd" "$home/bin/nabu.cmd"
chmod +x "$home/bin/nabu"
printf '%s\n' "$version" > "$home/current.new"
mv "$home/current.new" "$home/current"
say "nabu $version is installed in $home"

# --- Java
if ! "$java" -version >/dev/null 2>&1; then
    say "note: no Java found ($java). Nabu needs a JDK 21 or later: install one, or set NABU_JAVA_HOME or JAVA_HOME."
fi

# --- the PATH
case ":$PATH:" in
    *":$home/bin:"*) onpath=yes ;;
    *) onpath=no ;;
esac
if [ "$onpath" = no ]; then
    case "${SHELL:-}" in
        */zsh) profile="$HOME/.zshrc"; line="export PATH=\"$home/bin:\$PATH\"" ;;
        */bash) profile="$HOME/.bashrc"; line="export PATH=\"$home/bin:\$PATH\"" ;;
        */fish) profile="$HOME/.config/fish/config.fish"; line="fish_add_path \"$home/bin\"" ;;
        *) profile="$HOME/.profile"; line="export PATH=\"$home/bin:\$PATH\"" ;;
    esac
    answer=no
    if [ "$modify" = yes ]; then
        answer=yes
    elif [ "$modify" = ask ] && (exec 3< /dev/tty) 2>/dev/null; then
        # a terminal that can be opened (under curl | sh stdin is the script, so the answer comes from the terminal)
        printf 'Add %s to your PATH by appending this line to %s?\n    %s\n[y/N] ' "$home/bin" "$profile" "$line" > /dev/tty
        read -r reply < /dev/tty || reply=
        case "$reply" in y|Y|yes|YES) answer=yes ;; esac
    fi
    if [ "$answer" = yes ]; then
        mkdir -p "$(dirname "$profile")"
        if [ -f "$profile" ] && grep -qxF "$line" "$profile"; then
            say "$profile already has that line. Open a new terminal to use nabu."
        else
            printf '\n# added by the nabu install script\n%s\n' "$line" >> "$profile"
            say "Added to $profile. Open a new terminal (or run: . $profile) to use nabu."
        fi
    else
        say ""
        say "$home/bin is not on your PATH. To use nabu, add it yourself:"
        say "    $line      (put this in $profile)"
        say "or run nabu with its full path: $home/bin/nabu"
    fi
fi

say ""
say "Next: nabu doctor     (checks what is needed on this machine)"
if [ "$native" = yes ]; then
    "$home/bin/nabu" doctor || say "(nabu doctor lists what is missing for the native backend)"
else
    say "      nabu self-update --native     (adds the native backend)"
fi

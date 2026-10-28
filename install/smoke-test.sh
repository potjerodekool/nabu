#!/bin/sh
# Tries an installation of Nabu with the native backend: the commands, and a native build of a small program.
#   usage: smoke-test.sh <nabu home>
# Used by the release workflow after install.sh or install.ps1 has installed a release made by that workflow.
set -eu

home="${1:?usage: smoke-test.sh <nabu home>}"
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) nabu="$home/bin/nabu.cmd"; exe=hello.exe ;;
    *) nabu="$home/bin/nabu"; exe=hello ;;
esac

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

echo "== nabu version"
"$nabu" version
echo "== nabu doctor"
"$nabu" doctor

echo "== a program, built to a native executable"
mkdir -p "$work/classes"
cat > "$work/Hello.java" <<'EOF'
public class Hello {
    public static void main(String[] args) {
        System.out.println("hello " + args.length);
    }
}
EOF
javac -d "$work/classes" "$work/Hello.java"
jar --create --file "$work/hello.jar" --main-class Hello -C "$work/classes" .
"$nabu" native-image -d "$work/out" -jar "$work/hello.jar"
actual=$("$work/out/$exe")
echo "the executable says: $actual"
[ "$actual" = "hello 0" ] || { echo "smoke test: expected 'hello 0'" >&2; exit 1; }
echo "smoke test passed"

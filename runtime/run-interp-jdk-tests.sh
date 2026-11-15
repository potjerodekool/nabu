#!/bin/bash
# Phase 3 of the interpreter: reflection, ServiceLoader, Proxy and ClassLoader through the real JDK classes.
# POSIX only (dlfcn/pthread). Needs a JDK on PATH (javac, jimage). Usage: runtime/run-interp-jdk-tests.sh
# Builds the fixtures the interpreter reads from $NABU_DEV_DIR: jdkclasses/classes (java.base, extracted with
# jimage) and services.txt; the test classes come from runtime/jtest through NABU_CLASSPATH.
set -u
R="$(cd "$(dirname "$0")" && pwd)"
O="${TMPDIR:-/tmp}/nabu-interp-jdk-tests"; rm -rf "$O"; mkdir -p "$O/classes" "$O/dev/jdkclasses"
javac --release 17 -d "$O/classes" "$R"/jtest/*.java || exit 1
cp -r "$R"/jtest/META-INF "$O/classes/"   # service files, found through NABU_CLASSPATH
JH="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
jimage extract --dir "$O/x" --include 'regex:/java.base/java/(lang|util|io|net|nio|security|text|time)/.*' "$JH/lib/modules" || exit 1
mkdir -p "$O/dev/jdkclasses/classes"; cp -r "$O"/x/java.base/* "$O/dev/jdkclasses/classes/"
printf 'ServiceTest$Greeter=ServiceTest$En\n' > "$O/dev/services.txt"
export NABU_DEV_DIR="$O/dev" NABU_CLASSPATH="$O/classes"
gcc -w -DNABU_GC_NONE -I"$R" -o "$O/test_run" "$R/test_run.c" "$R"/nabu_runtime.c "$R"/gc_none.c "$R"/nabu_interpreter.c "$R"/nabu_classfile.c "$R"/test_stubs.c -lpthread -ldl -lm || exit 1
fail=0
# class  expected-output (| separated lines)
check() {
  local out want; out=$(cd "$O" && timeout 60 ./test_run "$O/classes/$1.class" "$1" 2>&1)
  want=$(echo "$2" | tr '|' '\n')
  if [ "$out" == "$want" ]; then echo "PASS $1"; else echo "FAIL $1"; echo "--- got"; echo "$out"; echo "--- want"; echo "$want"; fail=1; fi
}
check ReflectMethodTest '42'
check ReflectTest '42|7|42|count|7|99|x'
check ProxyTest 'hello bob via greet'
check ProxyLambdaTest 'hi bob via greet|true|false'
check LoaderTest 'loader|java.lang.String'
check ServiceTest 'hello|1'
check ServiceMetaTest 'hello meta|1'
check StaticTest 'static-5|6|7|7|int|true'
exit $fail

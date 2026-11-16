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
# a jar with a class and a service provider that exist nowhere else: read from the zip by the runtime
mkdir -p "$O/jarclasses"
javac --release 17 -cp "$O/classes" -d "$O/jarclasses" "$R"/jtest-jar/*.java || exit 1
cp -r "$R"/jtest-jar/META-INF "$O/jarclasses/"
jar cf "$O/lib.jar" -C "$O/jarclasses" . || exit 1
JH="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
jimage extract --dir "$O/x" --include 'regex:/java.base/java/(lang|util|io|net|nio|security|text|time)/.*' "$JH/lib/modules" || exit 1
mkdir -p "$O/dev/jdkclasses/classes"; cp -r "$O"/x/java.base/* "$O/dev/jdkclasses/classes/"
printf 'ServiceTest$Greeter=ServiceTest$En\n' > "$O/dev/services.txt"
export NABU_DEV_DIR="$O/dev" NABU_CLASSPATH="$O/classes:$O/lib.jar"
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
check BoxTest '6|5|true|true|true|x|8|4|2|flag=false zero=0 c=q s=null b=true'
check ReflectBoxTest 'true|k|3|false|false|5|b|true'
check ReflectInheritTest '8|derived|11|not declared|no such method'
check JarLoadTest '42|from jar'
check VirtualTest '6|27|54|27'
# the method JIT (runtime/jit, needs cargo): the same output with and without it, and with it for the compiled calls
if command -v cargo >/dev/null 2>&1; then
  (cd "$R/jit" && cargo build --release -q) || exit 1
  export NABU_JIT_LIB="$R/jit/target/release/libnabu_jit.so" NABU_JIT_THRESHOLD=5
  out_jit=$(cd "$O" && timeout 60 ./test_run "$O/classes/JitTest.class" JitTest 2>&1)
  out_int=$(cd "$O" && NABU_JIT=0 timeout 60 ./test_run "$O/classes/JitTest.class" JitTest 2>&1)
  if [ "$out_jit" == "$out_int" ] && echo "$out_jit" | grep -q 'div0'; then echo "PASS JitTest"; else echo "FAIL JitTest"; echo "--- jit"; echo "$out_jit"; echo "--- interpreted"; echo "$out_int"; fail=1; fi
  out_jit=$(cd "$O" && timeout 60 ./test_run "$O/classes/JitObjTest.class" JitObjTest 2>&1)
  out_int=$(cd "$O" && NABU_JIT=0 timeout 60 ./test_run "$O/classes/JitObjTest.class" JitObjTest 2>&1)
  if [ "$out_jit" == "$out_int" ] && echo "$out_jit" | grep -q 'aioobe'; then echo "PASS JitObjTest"; else echo "FAIL JitObjTest"; echo "--- jit"; echo "$out_jit"; echo "--- interpreted"; echo "$out_int"; fail=1; fi
  out_jit=$(cd "$O" && timeout 60 ./test_run "$O/classes/JitVirtTest.class" JitVirtTest 2>&1)
  out_int=$(cd "$O" && NABU_JIT=0 timeout 60 ./test_run "$O/classes/JitVirtTest.class" JitVirtTest 2>&1)
  if [ "$out_jit" == "$out_int" ] && [ -n "$out_jit" ]; then echo "PASS JitVirtTest"; else echo "FAIL JitVirtTest"; echo "--- jit"; echo "$out_jit"; echo "--- interpreted"; echo "$out_int"; fail=1; fi
  unset NABU_JIT_LIB NABU_JIT_THRESHOLD
fi
check WideParamsTest '3.75|115|1.5'
exit $fail

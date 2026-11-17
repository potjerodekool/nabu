#!/bin/bash
# Builds and runs the interpreter tests (POSIX only: needs dlfcn/pthread). Usage: runtime/run-interp-tests.sh
# Compiles runtime/jtest/*.java with the JDK on PATH, builds each test with gcc (-DNABU_GC_NONE) and checks its output.
set -u
R="$(cd "$(dirname "$0")" && pwd)"
O="${TMPDIR:-/tmp}/nabu-interp-tests"; rm -rf "$O"; mkdir -p "$O/classes"
javac --release 17 -d "$O/classes" "$R"/jtest/*.java || exit 1
export NABU_CLASSPATH="$O/classes"
fail=0
build() { gcc -w ${NABU_TEST_CFLAGS:--O2 -fno-strict-aliasing} -DNABU_GC_NONE -I"$R" -o "$O/$1" "$R/$2.c" "$R"/nabu_runtime.c "$R"/gc_none.c "$R"/nabu_interpreter.c "$R"/nabu_classfile.c "$R"/test_stubs.c -lpthread -ldl -lm; }
# name  test-source  class  expected-output (| separated lines)
check() {
  local name=$1 src=$2 cls=$3 expect=$4
  build "$name" "$src" || { echo "FAIL $name (build)"; fail=1; return; }
  local out; out=$(cd "$O" && timeout 30 "./$name" "$O/classes/$cls.class" 2>&1 | grep -v -e '^main returned' -e '^interp: unknown method' -e '^main:' -e '^[0-9a-f][0-9a-f] ' -e '^$' -e '^add(3,4)')
  local want; want=$(echo "$expect" | tr '|' '\n')
  if [ "$out" == "$want" ]; then echo "PASS $name"; else echo "FAIL $name"; echo "--- got"; echo "$out"; echo "--- want"; echo "$want"; fail=1; fi
}
for t in test_interpreter test_interpreted_type; do
  build "$t" "$t" && (cd "$O" && ./"$t" | grep -q '^0 failures' && echo "PASS $t" || { echo "FAIL $t"; fail=1; }) || fail=1
done
check test_classfile test_classfile Hello '7|120|hello world|36'
check test_arr test_arr ArrTest '50|3|A|9|x'
check test_obj test_obj ObjTest '42|hello|99'
check test_exc test_exc ExcTest '5|-1|42|7|8'
check test_lambda test_lambda LambdaTest '7|17'
check test_strconcat test_strconcat StrConcat 'n=5 s=abc'
exit $fail

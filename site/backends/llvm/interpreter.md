---
layout: page
title: Bytecode interpreter and JIT (experimental)
---

The runtime of the [LLVM backend]({{ site.baseurl }}/backends/llvm/) has an optional **bytecode interpreter**, with an optional **JIT compiler** for hot methods. The interpreter is what makes the [open world mode]({{ site.baseurl }}/backends/llvm/open-world/) possible: it runs classes that were not part of the build. This page describes what the interpreter and the JIT can do, how to run them, and where they stop. It is meant for people who want to use or work on them.

<div class="callout callout-warning" markdown="1">
**Experimental.** The interpreter is not a JVM. It runs a growing part of the JDK, mostly through natives, and fails with `interp: unknown method …` on what it does not know. A native executable only reaches it through the narrow bridge of the [open world page]({{ site.baseurl }}/backends/llvm/open-world/#limitations).
</div>

## Where the code is

| File | Content |
|---|---|
| `runtime/nabu_interpreter.c`, `nabu_interpreter.h` | frames, the opcode loop, invocation, exceptions, closures (lambdas), proxies, the JIT hook |
| `runtime/nabu_classfile.c` | the class file reader (directories and jars, with a zip reader and an inflater of its own), the registry of classes and fields, and the natives (`register_native`) that stand in for the JDK |
| `runtime/jit/` | the JIT: a Rust crate `nabu_jit` (a `cdylib`) that uses [Cranelift](https://cranelift.dev/) |
| `runtime/test_run.c` | a driver that runs `main` of a class file on the interpreter alone: `test_run <file.class> <ClassName>` |
| `runtime/jtest/`, `runtime/run-interp-jdk-tests.sh`, `runtime/run-interp-tests.sh` | the tests (see [Testing](#testing)) |

## How it works

- **One slot per value.** The operand stack and the local variables are arrays of `void *`. An `int` is its value, a `long` or `double` is one slot too (its 64 bits; `float` its 32 bits). JVM local variable numbers are kept (a `long` or `double` takes two numbers), and `pop2` and the `dup2` family look at a per-method analysis of the bytecode to know which entries are wide.
- **Objects** are a 16-byte header (a pointer to the type information, then the monitor words) followed by one 8-byte slot per field, with the fields of a superclass first. **Arrays** are the length (4 bytes), the element size (4 bytes) and the elements. A **`String`** is a plain C string (`char *`): the interpreter does not use the `String` layout of the compiled runtime or of the JDK.
- **Boxes.** An `Integer` (and a `Short`, a `Byte`, and a `Long` in the range of an `int`) is its raw value; a boxed 0 of any number type is a tagged value, so that it is not the null pointer. A `Boolean` and a `Character` are tagged values too. A `Double`, a `Float` and a `Long` beyond the `int` range are objects of the JDK class with their bits in the field `value`.
- **Class objects** are interned: `X.class`, `getClass()` and `Class.forName` give the same object for the same class.
- **Dispatch.** `invokevirtual` and `invokeinterface` look at the class of the receiver and walk up through the superclasses and the interfaces (default methods come last). A native of the interpreter wins over JDK bytecode of the same method. A native that is registered on an interface (`Iterator`, `Comparable`, `CharSequence`) hands the call to the implementation of the receiver when that is an object of the interpreter.
- **Exceptions.** `try`/`catch`/`finally` work, and a `catch` type matches through the class hierarchy. Code of the program throws `NullPointerException` for a null array, field access or receiver, `ArrayIndexOutOfBoundsException` for a bad index and `ArithmeticException` for a division by zero. The JDK classes are lenient: a null array reads as 0 and a store is dropped, because they run without all of their static initialization.
- **`invokedynamic`:** string concatenation and lambdas (`LambdaMetafactory`, including method and constructor references) are supported.
- **`monitorenter` and `monitorexit`** do nothing: the interpreter runs one thread at a time.
- **Instructions not supported:** `jsr` and `ret`.

## The JDK in the interpreter

There are two sources:

1. **Natives.** `nabu_classfile.c` has several hundred of them: `String`, `StringBuilder`, `StringJoiner`, `Math`, `Character`, the wrapper classes, `Arrays`, `System.arraycopy`, `Class` and reflection (`getMethod`, `getDeclaredField`, `invoke`, `newInstance`, …), `Proxy`, `ServiceLoader`, `Unsafe`, `SharedSecrets` and a few more. A native has the name, the class and the descriptor of the method it replaces.
2. **JDK bytecode.** When the directory `$NABU_DEV_DIR/jdkclasses/classes` exists (default `NABU_DEV_DIR` is `/tmp/opencode`), classes the interpreter does not know are read from there: the JDK's own bytecode of `java.base`. The test script builds it with `jimage extract --dir x --include 'regex:/java.base/(java/(lang|util|io|net|nio|security|text|time|math)|jdk/internal/(util|access|math))/.*' $JAVA_HOME/lib/modules`.

**Static initializers of JDK classes** run for `java/util/`, `java/math/`, `java/time/` and `java/lang/` (not `java/lang/reflect/`, which the natives handle). The environment variable `NABU_JDK_CLINIT` replaces that list: prefixes separated by a colon, a prefix with `-` in front excludes, and an empty value switches the initializers off.

What runs, checked against the output of a real JVM by the tests: `ArrayList`, `LinkedList`, `HashMap` and `HashSet` also with your own classes as keys (with `equals` and `hashCode`), `TreeMap`, `LinkedHashMap`, `EnumMap`, `EnumSet`, `Collections.sort`, `Arrays`, streams and collectors (`map`, `filter`, `collect`, `joining`, `partitioningBy`, `toList`, `reduce`), `Optional`, `String.join`, `String.split` for a literal separator or `\s+`, `BigInteger`, `LocalDate`, `Random`, `Class.forName`, `Proxy`, `ServiceLoader` (also from `META-INF/services` in a jar) and enums with constant bodies.

What does not: the parts of the JDK that work on the byte arrays of the real `String` (`StringConcatHelper`, `Unsafe.allocateUninitializedArray`), regular expressions beyond the cases above, threads in the interpreter, and the many JDK classes nobody has tried. The message `interp: unknown method <class>.<method><descriptor>` names what is missing.

## The JIT

The interpreter counts the calls of each method. A method that has been called 100 times, or that contains a loop (at its first call), is handed to `libnabu_jit`, which turns its bytecode into machine code with Cranelift. What the JIT does not understand stays interpreted.

**What is compiled:** static and instance methods of the program, not JDK classes. Everything with `int`, `long`, `float` and `double` arithmetic, conversions and comparisons; branches and loops; `switch` (`tableswitch` and `lookupswitch`, when nothing else is on the stack); `try`, `catch` and `finally`; arrays of every type with null and bounds checks; fields and statics; `new` and constructors; `instanceof`, `checkcast` and `athrow`; calls of static, special, virtual and interface methods. A virtual or interface call looks at the class of the receiver, with a cache per call site. A recursive call of the method itself goes straight to the machine code. Calls of JDK methods and natives, `invokedynamic` and `getstatic` of JDK fields (such as `System.out`) are done by the interpreter, through a one-instruction method.

**Where it is found:** `nabu_jit_init` is loaded with `dlopen` from the file named by `NABU_JIT_LIB`, or else from `libnabu_jit.so` on the library path. There is no JIT on Windows. Build it with `cargo build --release` in `runtime/jit` (Rust, and the crates `cranelift-codegen`, `-frontend`, `-jit`, `-module`, `-native`).

| Variable | Meaning |
|---|---|
| `NABU_JIT=0` | do not use the JIT |
| `NABU_JIT_LIB=<file>` | the JIT library |
| `NABU_JIT_THRESHOLD=<n>` | number of calls before a method is compiled (default 100) |
| `NABU_JIT_TRACE=1` | print each method that is compiled or not, and the opcode that was the reason it was not |

A compiled method is a C function `int64_t f(const int64_t *args)`: one 64-bit value per argument, and the result in the same form.

**What it gains.** Measured on x86-64 under WSL2, one process each, with the JIT and with `NABU_JIT=0`:

| Program | Interpreted | With the JIT |
|---|---|---|
| recursive `fib(27)` | 0.79 s | 0.03 s |
| a loop of 3 million iterations in one method | 2.3 s | 0.03 s |
| a sieve of 2 million numbers and a 300×300 matrix | 4.9 s | 0.04 s |
| a loop with polymorphic virtual calls and fields | 2.75 s | 0.15 s |

These are small programs that spend all their time in compiled methods. Code that mostly calls JDK methods gains little, since those calls go through the interpreter.

## Environment variables

| Variable | Meaning |
|---|---|
| `NABU_CLASSPATH` | directories and jars with the classes to load (`:` or `;`) |
| `NABU_DEV_DIR` | the directory with `jdkclasses/classes` and `services.txt` (default `/tmp/opencode`) |
| `NABU_JDK_CLINIT` | prefixes of the JDK classes whose static initializer runs (see above) |
| `NABU_TRACE_EXC=1` | print the exceptions the interpreter creates, null receivers, array errors and the static initializers it runs |
| `NABU_TRACE_CALLS=1` | print every method that is invoked |
| `NABU_JIT`, `NABU_JIT_LIB`, `NABU_JIT_THRESHOLD`, `NABU_JIT_TRACE` | the JIT, see above |

## Testing

`runtime/run-interp-jdk-tests.sh` builds `test_run` with `gcc`, extracts the JDK classes with `jimage`, compiles the test classes of `runtime/jtest/` with `javac`, and runs them; most tests compare their output with the output of `java`. When `cargo` is on the path it also builds the JIT and compares every program with the JIT on, with the JIT off and with a real JVM. `runtime/run-interp-tests.sh` runs the smaller, older tests. Both are POSIX scripts (Linux, macOS, or WSL on Windows); a JDK must be on the `PATH`.

```
cd runtime
bash run-interp-jdk-tests.sh      # prints PASS or FAIL per test
bash run-interp-tests.sh
```

`OpenWorldInterpreterE2eIT` (module `native-backend`) tests the interpreter inside a native executable.

## Known gaps

- A `String` is a C string, so the JDK code that works on the byte array of a `String` does not run. The cases that mattered have natives.
- The JIT does not compile JDK classes, a `switch` with other values on the stack, `multianewarray`, and `jsr`/`ret`.
- Objects and strings cannot cross the border between compiled code and the interpreter in a native executable.
- The interpreter is not thread safe: it runs one thread at a time, and `synchronized` excludes nothing.

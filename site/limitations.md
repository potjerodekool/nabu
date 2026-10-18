---
layout: page
title: Known Limitations
---

This page lists what does not work yet, as far as it is known. It is updated as limitations are found and fixed.

## Compiler (all backends)

- **Nested lambdas.** A lambda whose body is itself a lambda, such as `Supplier<IntSupplier> s = () -> () -> x + 1;`, makes the compiler crash: the inner lambda gets no target type. Use a method or an anonymous class for the inner function.
- **Java language level.** Java sources are parsed with a Java 20 grammar. Newer language features are not recognized.
- **Kotlin** support is experimental: only top-level class declarations are handled.
- The **Java backend** (ClassFile API) needs Java 24 or later to run.

## LLVM backend

### Building

- **`--bytecode-classes` is required in practice.** Without it, no JDK code is compiled and even `System.out.println` has no implementation. `--bytecode-classes=java.lang.Object` is enough for most programs.
- **Slow builds, large executables.** The whole reachable part of the JDK is compiled and optimized as one LLVM module: several minutes and about 36 MB for "hello world", about ten minutes and 46 MB for the [picocli example](/examples/picocli-demo/).
- **Incremental builds** have not been tested with the LLVM backend; the examples build with `-Dnabu.incremental=false`.
- The executable is named after the **first** compiled class, not after the class with `main`.
- The build prints many warnings (for example about static fields of classes outside the compiled closure). They do not stop the build, and most are harmless, but they make real problems harder to spot.

### Platforms

- **Windows x64 with MinGW-w64** is the only verified platform.
- **Linux and macOS** are supported by the code but have not been tested.
- **MSVC** is not supported: the exception handling needs DWARF/LSDA unwind data, which the MSVC runtime does not provide.

### Run time

- **Native and VM-internal JDK methods** have no implementation unless the C runtime provides one. A program that calls such a method stops with `nabu: aanroep van een JDK-functie zonder implementatie: <name>` and exit code 127. All such methods are listed in `<name>.unresolved.txt` at build time.
- **Reflection** only covers classes listed in GraalVM `*-config.json` metadata in the class output directory, and only their **fields and annotations**. Metadata inside jars on the classpath is not read.
- **Static fields of classes outside the compiled closure** keep their default value (`0`/`null`); the build warns about each one.
- **No stack traces.** Exceptions print their class and message when they reach the top of the program; set `NABU_TRACE_NPE=1` to locate `NullPointerException`s. See [Troubleshooting](/backends/llvm/troubleshooting/).
- **Garbage collection:** only `--gc=BOEHM` is suitable for real programs. `REFCOUNT` leaks every object that escapes its frame and does not collect cycles; `NONE` allocates objects on the stack, so they are invalid once the creating function returns.
- **Runtime messages are in Dutch.**
- Parts of the class library are still implemented in C (`runtime/jcl_lite.c`) instead of compiled from the JDK. Those parts can behave differently from the JDK in edge cases.

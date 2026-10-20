---
layout: page
title: Known Limitations
---

This page lists what does not work yet, as far as it is known. It is updated as limitations are found and fixed.

## Compiler (all backends)

- **Java language level.** Java sources are parsed with a Java 20 grammar. Newer language features are not recognized.
- **Kotlin** support is experimental: only top-level class declarations are handled.
- The **Java backend** (ClassFile API) needs Java 24 or later to run.

## LLVM backend

### Building

- **Reflection, resources and proxies need configuration.** Classes that are only used through reflection, resources and dynamic proxies must be listed in GraalVM configuration files under `META-INF/native-image/` (see [the LLVM backend](/backends/llvm/#reflection-resources-and-proxies)), as with GraalVM `native-image`. Otherwise classes are not compiled, resources are not found, and `Proxy.newProxyInstance` throws.
- **Slow builds, large executables.** The whole reachable part of the JDK is compiled and optimized: about 30 seconds and 13 MB for a small program, about 45 seconds and 15 MB for the [picocli example](/examples/picocli-demo/), on an 8-core machine. LLVM's optimization and code generation run in parallel parts, so fewer cores means a slower build.
- **Incremental builds** have not been tested with the LLVM backend; the examples build with `-Dnabu.incremental=false`.
- The build prints one summary line per kind of warning (for example about static fields of classes outside the compiled closure); the details are in `<name>.warnings.txt`. They do not stop the build, and most are harmless.

### Platforms

- **Windows x64 with MinGW-w64** is the only verified platform.
- **Linux and macOS** are supported by the code but have not been tested.
- **MSVC** is not supported: the exception handling needs DWARF/LSDA unwind data, which the MSVC runtime does not provide.

### Run time

- **Native and VM-internal JDK methods** have no implementation unless the C runtime provides one. A program that calls such a method stops with `nabu: call to a JDK function without an implementation: <name>` and exit code 127. All such methods are listed, grouped by class, in `<name>.unresolved.txt` at build time.
- **Reflection** only covers classes and members listed in GraalVM `*-config.json` metadata (in the class output directory or in jars on the classpath). Fields, methods, constructors, generic types and annotations of those are available; a member that is not registered is not found (`NoSuchMethodException`).
- **Static fields of classes outside the compiled closure** keep their default value (`0`/`null`); the build lists them in `<name>.warnings.txt`.
- **Stack traces are approximate.** `getStackTrace()` and `printStackTrace()` list the methods, found from the symbol table of the executable, with file and line for code compiled from your sources (`at Main.run(Main.java:12)`). JDK and library methods have no line numbers (`Unknown Source`). Methods that LLVM inlined are mostly missing from the trace (the innermost one is kept when it is your code), and a stripped executable has no trace. An exception that reaches the top of the program prints `Exception in thread "main"` and its trace and exits with code 1. See [Troubleshooting](/backends/llvm/troubleshooting/).
- **Garbage collection:** only `--gc=BOEHM` (the default) is suitable for executables. `REFCOUNT` leaks every object that escapes its frame and does not collect cycles; `NONE` allocates objects on the stack, so they are invalid once the creating function returns.
- The VM-bound parts of the class library (`Class`, `System`, `Thread`, `Runtime`, reflection, `Unsafe`) are implemented in C (`runtime/jcl_lite.c`) instead of compiled from the JDK. They can behave differently from the JDK in edge cases.
- **Files:** `java.io.File` and the file streams are compiled from the JDK; the file-system natives are in C. `getCanonicalPath()` resolves links and the case of names on Windows (on other systems it uses `realpath`). A file stream that is not closed is closed when the garbage collector finds it unreachable (`--gc=BOEHM`), which can take a while, so close your streams: until then the file stays locked on Windows.
- **The class of an array** is known for every array: those compiled code allocated, and those the C runtime or `Array.newInstance` made (`String[] args`, the `byte[]` of a string, reflection and annotation arrays).

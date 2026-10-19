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
- **Annotation elements.** An element whose type is an annotation, an array of primitives or an array of enums cannot be read at run time (natively); `String`, primitive, `Class`, enum, `String[]` and `Class[]` elements can.
- **Slow builds, large executables.** The whole reachable part of the JDK is compiled and optimized: about 30 seconds and 13 MB for a small program, about 45 seconds and 15 MB for the [picocli example](/examples/picocli-demo/), on an 8-core machine. LLVM's optimization and code generation run in parallel parts, so fewer cores means a slower build.
- **Incremental builds** have not been tested with the LLVM backend; the examples build with `-Dnabu.incremental=false`.
- The build prints many warnings (for example about static fields of classes outside the compiled closure). They do not stop the build, and most are harmless, but they make real problems harder to spot.

### Platforms

- **Windows x64 with MinGW-w64** is the only verified platform.
- **Linux and macOS** are supported by the code but have not been tested.
- **MSVC** is not supported: the exception handling needs DWARF/LSDA unwind data, which the MSVC runtime does not provide.

### Run time

- **Native and VM-internal JDK methods** have no implementation unless the C runtime provides one. A program that calls such a method stops with `nabu: call to a JDK function without an implementation: <name>` and exit code 127. All such methods are listed in `<name>.unresolved.txt` at build time.
- **Reflection** only covers classes listed in GraalVM `*-config.json` metadata (in the class output directory or in jars on the classpath), and only their **fields and annotations**.
- **Static fields of classes outside the compiled closure** keep their default value (`0`/`null`); the build warns about each one.
- **No stack traces.** Exceptions print their class and message when they reach the top of the program; set `NABU_TRACE_NPE=1` to locate `NullPointerException`s. See [Troubleshooting](/backends/llvm/troubleshooting/).
- **Garbage collection:** only `--gc=BOEHM` (the default) is suitable for executables. `REFCOUNT` leaks every object that escapes its frame and does not collect cycles; `NONE` allocates objects on the stack, so they are invalid once the creating function returns.
- The VM-bound parts of the class library (`Class`, `System`, `Thread`, `Runtime`, reflection, `Unsafe`) are implemented in C (`runtime/jcl_lite.c`) instead of compiled from the JDK. They can behave differently from the JDK in edge cases.
- **Files:** `java.io.File` and the file streams are compiled from the JDK; the file-system natives are in C. `getCanonicalPath()` does not resolve links or the case of names, and a file stream that is not closed is not closed by the garbage collector either.
- **The class of an array** is known for arrays that compiled code or `Array.newInstance` allocated. Arrays that the C runtime creates itself (for example the `byte[]` inside a string made in C) report `Object[]`.

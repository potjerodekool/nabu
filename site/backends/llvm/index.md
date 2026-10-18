---
layout: page
title: LLVM Backend
---

The **LLVM backend** compiles a program to a **native executable** instead of JVM bytecode. The same Nabu and Java sources that run on the JVM through the [ASM backend](/backends/asm/) can be turned into a standalone `.exe`, without a JVM at run time.

<div class="callout callout-warning" markdown="1">
**Experimental.** The backend runs real programs: the [picocli example](/examples/picocli-demo/) compiles the whole picocli library from source and behaves exactly like the JVM version. It is not production-ready, though. Builds are slow, executables are large, and parts of the JDK are not supported yet. See [Known limitations](/limitations/).
</div>

## Getting started

1. Install the toolchain: see [Installing the LLVM toolchain](/backends/llvm/installation/).
2. Select the backend with `--backend=LLVM` and add the options below.
3. Build. The executable appears in the class output directory (`target/classes` with Maven).

## How it works

```
Nabu/Java sources ──► frontend ──► IR ──┐
                                        ├──► LLVM IR ──► object file ──► gcc/clang ──► executable
JDK bytecode (.class) ──► bytecode lowering ──► IR ─┘                        ▲
                                                               C runtime (nabu_runtime.c, jcl_lite.c)
```

- **Your sources** go through the normal Nabu frontend and IR generation, the same as for the JVM backends.
- **The JDK is compiled too.** The backend does not ship its own Java class library. Starting from your code (and from the seed classes in `--bytecode-classes`), it follows every method that can be reached and compiles the **real OpenJDK bytecode** of those methods to the same IR. A one-line "hello world" pulls in about 3,200 JDK classes.
- **A small C runtime** provides what Java code cannot do itself: the object header and type information, exceptions, garbage collection hooks, monitors, string and number helpers, reflection metadata and the VM-level parts of `java.lang` (for example `Class`, `System.arraycopy` and `Unsafe`). It is compiled and linked into every executable.
- **LLVM** (through the Bytedeco Java bindings, inside the compiler process) turns the IR into an optimized object file, and the platform C compiler links it with the runtime.

## Compiler options

The options are passed like any other Nabu compiler option. With Maven, use `<compilerArgs>` in the `nabu-maven-plugin` configuration, for example `<arg>--backend=LLVM</arg>`.

| Option | Values | Default | Meaning |
|---|---|---|---|
| `--backend` | `ASM`, `JAVA`, `LLVM` | `ASM` | Selects the backend. |
| `--gc` | `NONE`, `BOEHM`, `REFCOUNT` | `NONE` | Memory management, see below. Use `BOEHM` for real programs. |
| `--bytecode-classes` | comma-separated class names | (none) | Switches on compiling the JDK from bytecode and gives the seed classes. `java.lang.Object` is enough for most programs. Without it, JDK methods have no implementation. |
| `--bytecode-jdk-home` | path to a JDK | the running JDK | Reads JDK bytecode from another JDK installation. |
| `--opt` | `NONE`, `DEFAULT`, `AGGRESSIVE` | `DEFAULT` | LLVM optimization level. |

## Choosing a GC strategy

| Strategy | Allocation | Freeing | Use for |
|---|---|---|---|
| `BOEHM` | `GC_malloc` (Boehm GC, linked with `-lgc`) | by the collector | real programs (recommended) |
| `REFCOUNT` | `malloc` | `nabu_release` when the owning reference dies | experiments; leaks objects that escape a frame, does not collect cycles |
| `NONE` | stack `alloca` in the allocating function | when that function returns | tiny tests only: objects become invalid when the function that created them returns |

With `BOEHM`, `GC_init` runs from `@llvm.global_ctors` before `main` and before any allocation. Every object, including ones created by the runtime, goes through `GC_malloc`.

## Reflection

Native executables have no class files to inspect at run time, so reflection works from **metadata generated at build time**. The backend reads GraalVM-style configuration files (`META-INF/native-image/**/*-config.json`) from the class output directory (and its parent directory), and emits **field and annotation** tables for the classes listed there. That is enough for frameworks that find annotated fields, such as picocli; reflective method invocation is limited.

This is the same format GraalVM `native-image` uses, so tools that generate it work for Nabu too. picocli's annotation processor (`picocli-codegen`) writes these files into the class output, which is how the [picocli example](/examples/picocli-demo/) gets its metadata. Configuration files inside jars on the classpath are **not** read.

## Output

For a program with a `main` method the backend writes three files to the class output directory, named after the **first** compiled class:

| File | Content |
|---|---|
| `<name>.ll` | LLVM IR in text form; useful for debugging |
| `<name>.o` | the object file |
| `<name>.exe` (Windows) or `<name>` | the linked executable |
| `<name>.unresolved.txt` | JDK functions that are called but could not be compiled (native methods, signature-polymorphic methods, missing classes). The program aborts with exit code 127 if it reaches one of them. |

Without a `main` method only the `.ll` and `.o` files are written.

## Platforms

| Platform | Toolchain | Status |
|---|---|---|
| Windows x64 | MinGW-w64 `gcc` (SEH) | supported, verified |
| Linux x64 | `gcc` | supported by the code, not verified |
| macOS x64 / ARM64 | `clang` | supported by the code, not verified |
| Windows x64 MSVC | — | not supported (no DWARF/LSDA unwinding in the MSVC runtime) |

## More

- [Installing the LLVM toolchain](/backends/llvm/installation/)
- [Example: a picocli command-line app](/examples/picocli-demo/)
- [Internals](/backends/llvm/internals/): how the backend is built, for contributors
- [Troubleshooting](/backends/llvm/troubleshooting/)
- [Known limitations](/limitations/)

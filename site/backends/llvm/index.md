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
- **The JDK and your libraries are compiled too.** The backend does not ship its own Java class library. Starting from your code and from the classes in the reflection configuration (see [Reflection](#reflection)), it follows every method that can be reached and compiles the **real bytecode** of those methods to the same IR: OpenJDK classes from the JDK, library classes from the jars on the classpath. You do not need the sources of a library. A one-line "hello world" pulls in about 3,200 JDK classes.
- **A small C runtime** provides what Java code cannot do itself: the object header and type information, exceptions, garbage collection hooks, monitors, string and number helpers, reflection metadata and the VM-level parts of `java.lang` (for example `Class`, `System.arraycopy` and `Unsafe`). It is compiled and linked into every executable.
- **LLVM** (through the Bytedeco Java bindings, inside the compiler process) turns the IR into an optimized object file, and the platform C compiler links it with the runtime.

## Compiler options

The options are passed like any other Nabu compiler option. With Maven, use `<compilerArgs>` in the `nabu-maven-plugin` configuration, for example `<arg>--backend=LLVM</arg>`.

| Option | Values | Default | Meaning |
|---|---|---|---|
| `--backend` | `ASM`, `JAVA`, `LLVM` | `ASM` | Selects the backend. |
| `--gc` | `NONE`, `BOEHM`, `REFCOUNT` | `BOEHM` | Memory management, see below. |
| `--bytecode-jdk-home` | path to a JDK | the running JDK | Reads JDK bytecode from another JDK installation. |
| `--opt` | `NONE`, `DEFAULT`, `AGGRESSIVE` | `DEFAULT` | LLVM optimization level. |

## Choosing a GC strategy

| Strategy | Allocation | Freeing | Use for |
|---|---|---|---|
| `BOEHM` (default) | `GC_malloc` (Boehm GC, linked with `-lgc`) | by the collector | real programs |
| `REFCOUNT` | `malloc` | `nabu_release` when the owning reference dies | experiments; leaks objects that escape a frame, does not collect cycles |
| `NONE` | stack `alloca` in the allocating function | when that function returns | not for executables: the JDK startup code keeps objects past the function that created them, so the program crashes before `main` |

With `BOEHM`, `GC_init` runs from `@llvm.global_ctors` before `main` and before any allocation. Every object, including ones created by the runtime, goes through `GC_malloc`.

## Reflection, resources and proxies

A native executable has no class files or class path at run time. As with GraalVM `native-image`, what a program uses dynamically must be known at build time, from GraalVM configuration files under `META-INF/native-image/`. The backend reads them from the class output directory and from every directory and jar on the classpath, so a library can ship its own configuration. It is the same format GraalVM uses, so tools that generate it work for Nabu too. picocli's annotation processor (`picocli-codegen`) writes these files into the class output, which is how the [picocli example](/examples/picocli-demo/) gets its metadata.

| File | What it does |
|---|---|
| `reflect-config.json` | The listed classes are compiled even when no code calls them, with the constructors and methods the entry names (`allDeclaredConstructors`, `allPublicMethods`, `methods`, …). The backend emits **field, method and annotation** metadata for them: the fields named in `fields`, or all of them with `allDeclaredFields` / `allPublicFields` (which includes the public fields of the superclasses), and the registered methods. |
| `resource-config.json` | The resources whose path matches an include pattern (`pattern` is a regular expression, `glob` a glob) and no exclude pattern are embedded in the executable. A bundle in `bundles` embeds its `.properties` files; its class-based variants are compiled for reflection. |
| `proxy-config.json` | A proxy class is generated at build time for each interface list. |
| `reachability-metadata.json` | The newer single file: its `reflection` (including `"type": {"proxy": […]}`), `resources` and `bundles` sections. |
| `native-image.properties` | From `Args`: `-H:ReflectionConfigurationResources`, `-H:ResourceConfigurationResources` and `-H:DynamicProxyConfigurationResources` (with `${.}` for the directory of the file), their `…ConfigurationFiles` variants, `-H:ConfigurationFileDirectories`, `-H:IncludeResources`, `-H:ExcludeResources` and `-H:IncludeResourceBundles`. Other options have no meaning for this backend and are ignored. |

At run time:

- **Fields.** `getDeclaredFields`, `getFields`, `getField` (public, also inherited), `Field.get`/`set`, the typed accessors (`getInt`, `setLong`, …, with the JDK's widening), `getModifiers`, `getGenericType` and the annotations work on the registered fields, static ones included (the class is initialized first).
- **Methods and constructors.** `getDeclaredMethods`, `getMethods` (public, also inherited, overrides hidden), `getMethod`, `getDeclaredMethod` and the constructor counterparts return the registered members, with name, parameter and return types, generic types (`ParameterizedType`, `TypeVariable`, `WildcardType`, `GenericArrayType`), modifiers, declared exceptions, annotations (also of the parameters) and `toString`. `Method.invoke` calls instance (with virtual dispatch), static and private methods; `Constructor.newInstance` constructs with any arguments (an abstract class throws `InstantiationException`); both wrap what the member throws in an `InvocationTargetException`. A member that is not registered is not found (`NoSuchMethodException`).
- **Resources.** `Class.getResource[AsStream]` (relative and absolute names), `ClassLoader.getResource[s|AsStream]` and `ClassLoader.getSystemResource[s|AsStream]` read the embedded resources. A URL has the `resource:` protocol, and its `openStream()` works. A path that occurs in several classpath roots is embedded once per root: `getResource` finds the first, `getResources` enumerates all of them in classpath order. `ResourceBundle.getBundle` finds the bundles: a class bundle or else the properties, for each candidate locale, with the JDK's fallback to the default locale.
- **Proxies.** `Proxy.newProxyInstance` returns an instance of the generated class for that interface list; a list without configuration throws an `IllegalArgumentException`. The handler gets a `Method` with its name, parameter and return types and `toString`. `method.invoke(target, args)` works for the methods of a proxied interface, which covers handlers that delegate to a real object.

## Output

For a program with a `main` method the backend writes three files to the class output directory, named after the class with the `main` method:

| File | Content |
|---|---|
| `<name>.ll` | LLVM IR in text form; useful for debugging |
| `<name>.o`, `<name>.1.o`, … | the object files; a large program is compiled in parallel parts, one object file per part |
| `<name>.exe` (Windows) or `<name>` | the linked executable |
| `<name>.warnings.txt` | The details of the build warnings that the build only summarizes: static fields outside the compiled closure, calls without a declaration, and fields that cannot be accessed. |
| `<name>.unresolved.txt` | JDK functions that are called but could not be compiled (native methods, signature-polymorphic methods, missing classes). The program aborts with exit code 127 if it reaches one of them. |

Without a `main` method only the `.ll` and object files are written.

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

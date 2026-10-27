---
layout: page
title: nabu-native-image
---

`nabu-native-image` compiles a Java program that is already built (a jar, or a directory of classes) to a native executable with the [LLVM backend](/backends/llvm/). It is used like GraalVM's `native-image`:

```
nabu-native-image [options] class [imagename]
nabu-native-image [options] -jar jarfile [imagename]
```

```
nabu-native-image -cp app.jar:lib/* com.acme.Tool        # builds ./tool
nabu-native-image -jar tool.jar -o tool -d build/bin     # Main-Class and Class-Path from the manifest
nabu-native-image --doctor                               # checks gcc/clang, libgc and the runtime
```

## Options

| Option | Meaning |
|---|---|
| `-cp`, `--class-path` | jars and directories; `dir/*` adds the jars of `dir` |
| `-jar` | the program is a jar: `Main-Class` and `Class-Path` come from its manifest |
| `-o` | name of the executable (default: the class name in lower case, or the name of the jar) |
| `-d`, `--output-dir` | where the executable is written (default: the current directory) |
| `--gc` | `BOEHM` (default), `REFCOUNT` or `NONE` |
| `-O0` … `-O3`, `--opt` | `NONE`, `DEFAULT` or `AGGRESSIVE` |
| `--keep-work` | keep the directory with the intermediate files (`.nabu-build-<name>`) |
| `--doctor` | check what a build needs |

Options of `native-image` that have no meaning here (`-H:…`, `-R:…`, `--initialize-at-…`, `-march=…`, …) are accepted and ignored with a warning, so existing command lines keep working. `--no-fallback` is accepted too, but not checked yet.

The `META-INF/native-image` configuration of the jars (reflection, resources, proxies) is read as GraalVM reads it, so the output of the GraalVM tracing agent can be used as is.

## What comes out

- `<name>` (`<name>.exe` on Windows), the executable;
- `lib/tzdb.dat` next to it: the time zone data the program reads at run time, which must stay next to the executable;
- `<name>.unresolved.txt` and `<name>.warnings.txt`: the JDK methods the program can reach but that are not implemented; the program stops when it executes one.

On Windows the executable needs the runtime libraries of the MinGW toolchain on the `PATH`, as for every program of this backend.

## Not there yet

A strict mode that fails the build on an unresolved JDK method, `ServiceLoader` providers, a faster build (the JDK part is compiled again every time), and a distribution that does not need Maven.

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
| `--strict`, `--no-fallback` | fail the build when the program or its libraries call a method that has no implementation (a `native` method of the program, a library method that could not be compiled) |
| `--static` | link the runtime of the toolchain into the executable. This is the default on Windows: the executable runs without the MinGW `bin` directory on the `PATH` |
| `--dynamic` | do not link it in (Windows): the executable needs the MinGW DLLs, `libwinpthread-1.dll`, on the `PATH` |
| `--keep-work` | keep the directory with the intermediate files (`.nabu-build-<name>`) |
| `--doctor` | check what a build needs |

Options of `native-image` that have no meaning here (`-H:…`, `-R:…`, `--initialize-at-…`, `-march=…`, …) are accepted and ignored with a warning, so existing command lines keep working.

The `META-INF/native-image` configuration of the jars (reflection, resources, proxies) is read as GraalVM reads it, so the output of the GraalVM tracing agent can be used as is.

## What comes out

- `<name>` (`<name>.exe` on Windows), the executable;
- `lib/tzdb.dat` next to it: the time zone data the program reads at run time, which must stay next to the executable;
- `<name>.unresolved.txt` and `<name>.warnings.txt`: the JDK methods the program can reach but that are not implemented; the program stops when it executes one.

On Windows the runtime of the MinGW toolchain (`libwinpthread`, `libgcc`) is linked into the executable, so it needs no DLLs of the toolchain and does not depend on which copy of `libwinpthread-1.dll` comes first on the `PATH`. With `--dynamic` it does: an older copy of that DLL from another program (Multipass, for example) then makes the executable fail to start with the error `0xC0000139`. On Linux and macOS nothing changes.

## Closed world

The executable contains only what the build can reach from `main`. Three things help it find the rest:

- **Configuration.** Reflection, resources and proxies come from `META-INF/native-image`. To write it for a program, run the program on a JVM with the GraalVM tracing agent (`-agentlib:native-image-agent=config-output-dir=META-INF/native-image`) and put the directory in a jar on the class path. The files are used as they are.
- **ServiceLoader.** The `META-INF/services/<service>` files of the jars on the class path are embedded, and the providers they name are compiled and created through reflection. `ServiceLoader.load(Service.class)` finds them as on a JVM. Providers of the JDK itself (module `provides`) are not listed: the JDK uses its built-in defaults.
- **Strict mode.** Without `--strict` a method without implementation only stops the program when it is executed. The JDK always has some of these (see `<name>.unresolved.txt`), so `--strict` only looks at the program and its libraries.

## Not there yet

A faster build (the JDK part is compiled again every time), a check of `Class.forName` calls against the configuration, and a distribution that does not need Maven.

## The runtime cache

The C runtime that every program is linked with is compiled once and kept in `~/.nabu/cache/runtime/<key>/` (another directory: `-Dnabu.cache.dir=...` or `NABU_CACHE_DIR`). The key is a hash of the runtime sources and headers, the compiler (its version), the target and the flags, so a new compiler or a changed runtime makes a new entry; old entries can be deleted at any time. `-Dnabu.runtime.cache=false` switches the cache off. It saves about 2.5 seconds per build.

## The distribution

`mvn package -pl native-image -am` puts a ready-to-use directory in `native-image/target/dist`:

```
bin/nabu-native-image        (Linux, macOS)
bin/nabu-native-image.cmd    (Windows)
lib/*.jar                    the compiler, the language support and the backend with the LLVM libraries
```

The launchers need a JDK 21 or later (`NABU_JAVA_HOME`, `JAVA_HOME` or the `PATH`); `NABU_JAVA_OPTS` holds options of the JVM that runs the compiler, for example `-Xmx3g`. The backend jar is about 500 MB, because it contains the LLVM libraries of its platform: a distribution is for one platform.


## Licenses

Nabu is under the Apache License 2.0. It does not contain the Java class library: the executable it builds contains the
OpenJDK classes the program can reach, compiled from the JDK that ran the build, and the file `lib/tzdb.dat` that is
copied from that JDK. The OpenJDK is under the GNU General Public License, version 2, with the
[Classpath Exception](https://openjdk.org/legal/gplv2+ce.html), which lets you distribute the executable under terms of
your choice. Nabu changes some of the JDK code it compiles, and that changed code stays under the OpenJDK license: when
you distribute an executable, include that license text and say where the source of the OpenJDK release can be had
(<https://openjdk.org/>). The file `NOTICE` in the root of the repository has the details.

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

- **Threads run one at a time.** `new Thread(runnable).start()` creates a real native thread, but a global lock lets only one thread run at any moment, so a program gets no speed-up from more threads. The lock is handed over where a thread blocks (`Thread.sleep`, `join`, `Object.wait`, `LockSupport.park`, a contended `synchronized`, `Thread.yield`) and at the end of the thread, and when a thread keeps reading the same `volatile` field or keeps calling `isInterrupted()` (the usual way to wait for another thread). A loop that waits on a field that is not `volatile` may never end, as on the JVM. Verified: a `Thread` subclass that overrides `run`, `start`, `join` (also with a `Duration`), `sleep`, `interrupt`, `wait`/`notify`, `synchronized`, a fixed `ExecutorService` with `Future`, `CompletableFuture`, `ForkJoinPool`, parallel streams and `java.util.Timer`. At the end of `main` the program waits for the threads that are not daemons, unless `System.exit` was called. An exception that escapes `run` is printed as `Exception in thread "name" …` and ends that thread only.
- **Not supported with threads:** uncaught-exception handlers, virtual threads, and a real thread group tree: every thread is in one group, `main`, which has no parent and does not list its subgroups.
- **Casts are checked as far as the runtime can tell**: a wrong `(Integer) object`, a cast to an interface of the program or a cast to an array type throws `ClassCastException`. A cast passes unchecked when the object was made by the C runtime or the target is a JDK interface that the program does not contain. Array indexes and integer division by zero are checked.
- **The JDK is only covered as far as it has been tried.** What runs: collections, streams, `String.format`, regex, `java.time` (including `LocalDate.now()`), `BigInteger` and `BigDecimal`, `Random`, `SecureRandom` and `UUID`, `MessageDigest` (MD5, SHA-1, SHA-2), `Mac` (HMAC), RSA key pairs and `SHA256withRSA` signatures, `ConcurrentLinkedQueue`, `ConcurrentLinkedDeque`, `ConcurrentSkipListMap` and the atomic classes (these use one thread: a compare-and-set is a plain read and write), `Cipher` (AES with CBC, ECB, CTR or GCM), `KeyGenerator` and `SecretKeyFactory` (PBKDF2) from the SunJCE provider (no jar verification of providers: all providers are trusted), `CRC32`, `Base64`, `Properties`, `URI`, `DecimalFormat`, `SimpleDateFormat` and the common `Files` methods (including `walk`, `walkFileTree`, `list`, `newDirectoryStream`, `copy`, `move`, `readAttributes` with basic and DOS attributes, `setLastModifiedTime`, `getFileStore`, `toRealPath` and `probeContentType`, which goes by the file extension), `RandomAccessFile`, file channels (`FileChannel.open`, `Files.newByteChannel`, `getChannel()`, direct buffers; no memory mapping, no file locks between processes, direct buffers are freed only by `Cleaner` runs, which do not happen) and `ProcessBuilder`/`Runtime.exec` (Windows only; the redirects `PIPE` and `INHERIT`, no file redirects, no `onExit`). What does not: serialization (`ObjectOutputStream`), other ciphers, other signature algorithms than RSA, and key stores, and other file attributes (POSIX permissions, ACLs, symbolic links; `getOwner` only gives the name of the user running the program). Other parts of the JDK have not been tried. Only the locale data of the root locale is included, so formatting and display names follow English conventions.
- **`lib/tzdb.dat`** is copied next to the executable at build time. The time zone database is read from there, so ship the `lib` directory with the executable. The default time zone comes from the `TZ` variable or from the Windows zone (a table of common zones); otherwise it is UTC.
- **`SecureRandom`** uses the random bytes of the operating system; the algorithm name is not one of the JDK's.
- **Native and VM-internal JDK methods** have no implementation unless the C runtime provides one. A program that calls such a method stops with `nabu: call to a JDK function without an implementation: <name>` and exit code 127. All such methods are listed, grouped by class, in `<name>.unresolved.txt` at build time.
- **Reflection** only covers classes and members listed in GraalVM `*-config.json` metadata (in the class output directory or in jars on the classpath). Fields, methods, constructors, generic types and annotations of those are available; a member that is not registered is not found (`NoSuchMethodException`).
- **Static fields of classes outside the compiled closure** keep their default value (`0`/`null`); the build lists them in `<name>.warnings.txt`.
- **Stack traces are approximate.** `getStackTrace()` and `printStackTrace()` list the methods, found from the symbol table of the executable, with file and line for code compiled from your sources (`at Main.run(Main.java:12)`). JDK and library methods have no line numbers (`Unknown Source`). Methods that LLVM inlined are mostly missing from the trace (the innermost one is kept when it is your code), and a stripped executable has no trace. An exception that reaches the top of the program prints `Exception in thread "main"` and its trace and exits with code 1. See [Troubleshooting](/backends/llvm/troubleshooting/).
- **Garbage collection:** only `--gc=BOEHM` (the default) is suitable for executables. `REFCOUNT` leaks every object that escapes its frame and does not collect cycles; `NONE` allocates objects on the stack, so they are invalid once the creating function returns.
- The VM-bound parts of the class library (`Class`, `System`, `Thread`, `Runtime`, reflection, `Unsafe`) are implemented in C (`runtime/jcl_lite.c`) instead of compiled from the JDK. They can behave differently from the JDK in edge cases.
- **Unclosed file streams** are only closed when the garbage collector finds them unreachable (`--gc=BOEHM`), which can take a while. Until then the file stays locked on Windows, so close your streams.

---
layout: page
title: Installing the LLVM toolchain
---

The LLVM backend turns a Nabu or Java program into a native executable. This page lists what you need to install, how Nabu finds those tools, and how to check that everything works.

<div class="callout callout-info" markdown="1">
**Verified on Windows x64 and Linux x64.** Every step for Windows on this page has been run on a real machine, and a native build has been run on Ubuntu (in WSL, `gcc` 15 and `libgc` 8.2 from the Ubuntu packages). The macOS section follows what the code expects (`clang` on macOS), but it has **not been tested**. Please report what you find. To install Nabu itself, see [Installing Nabu](/install/).
</div>

## What the backend needs

You do **not** need to install LLVM itself. The backend uses LLVM through the [Bytedeco](https://github.com/bytedeco/javacpp-presets/tree/master/llvm) Java bindings (`org.bytedeco:llvm` 21.1.8), which ship the native LLVM libraries inside a jar. LLVM produces an object file in-process.

To turn that object file into an executable, Nabu calls a **C compiler**. It compiles the Nabu C runtime (`nabu_runtime.c` and `jcl_lite.c`) and links everything together. Which compiler depends on the platform:

| Platform | C compiler / linker | Exception model |
|---|---|---|
| Windows x64 | MinGW-w64 `gcc` (SEH build) | Windows SEH with Nabu's own personality routine |
| Linux x64 | `gcc` | Itanium ABI (DWARF) |
| macOS x64 / ARM64 | `clang` | Itanium ABI (DWARF) |

Windows with the **MSVC** toolchain is not supported: Nabu's exception handling relies on DWARF/LSDA unwind data, which the MSVC runtime does not provide. Nabu stops with an explicit error instead of producing a broken executable.

For the default garbage collector (`--gc=BOEHM`, see [Choosing a GC strategy](/backends/llvm/#choosing-a-gc-strategy)) you also need the **Boehm-Demers-Weiser garbage collector** (`libgc` and the `gc.h` header) for that C compiler.

## Overview

| Component | Version verified | Needed for |
|---|---|---|
| JDK | Temurin 25.0.4 | Running Maven and the compiler. JDK 24 or later is required to build the whole Nabu project (the `java-backend` module uses the ClassFile API). |
| Maven | 4.0.0-beta-3 (the Maven that ships with mvnd 2.0.0-beta-1) | Building Nabu and your project |
| MinGW-w64 GCC (Windows) | 16.2.0, WinLibs build, UCRT, POSIX threads, SEH | Compiling the runtime and linking |
| Boehm GC | 8.2.12 | `--gc=BOEHM` |
| Nabu | built from source (`0.1.0-SNAPSHOT`) | The LLVM backend is not published to Maven Central |

## Windows x64

### 1. JDK and Maven

Install a JDK (25 is verified) and Maven, and make sure `java` and `mvn` work from a terminal.

The JDK matters a little more for the LLVM backend than usual. The backend compiles the JDK classes your program uses from bytecode, and it reads them from the **JDK the compiler runs on**. Use `--bytecode-jdk-home` to read them from a different JDK.

### 2. MinGW-w64 GCC

Install a 64-bit MinGW-w64 GCC that uses **SEH** exceptions. A build that has been verified is the [WinLibs](https://winlibs.com/) release *GCC 16.2.0 (UCRT runtime, POSIX threads), Win64*. Unpack it, for example to `C:\programs\mingw64`, so that `C:\programs\mingw64\bin\gcc.exe` exists.

Check the compiler:

```powershell
C:\programs\mingw64\bin\gcc.exe --version
# gcc.exe (MinGW-W64 x86_64-ucrt-posix-seh, built by Brecht Sanders, r1) 16.2.0
```

The `x86_64-…-seh` part matters. A 32-bit or SJLJ/DWARF-exception build will not work with Nabu's runtime.

The WinLibs package also contains `cmake.exe` and `mingw32-make.exe`, which the next step uses.

### 3. Boehm GC (libgc)

WinLibs does not include the Boehm GC, so build it once from source with the same GCC. This produces a static `libgc.a`, so your executables do not need an extra DLL.

1. Download the release archive `gc-8.2.12.tar.gz` from the [bdwgc releases](https://github.com/ivmai/bdwgc/releases/tag/v8.2.12) and unpack it.
2. Build and install it with the MinGW toolchain (PowerShell, from the unpacked `gc-8.2.12` directory):

   ```powershell
   $env:PATH = "C:\programs\mingw64\bin;" + $env:PATH
   cmake -S . -B build -G "MinGW Makefiles" `
         -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF -Denable_cplusplus=OFF `
         "-DCMAKE_INSTALL_PREFIX=C:\programs\bdwgc-8.2.12"
   cmake --build build -j 8
   cmake --install build
   ```

   This installs `include\gc.h`, `include\gc\…` and `lib\libgc.a` under `C:\programs\bdwgc-8.2.12`.

3. Make the headers and library visible to GCC. Either copy `include\*` and `lib\libgc.a` into the MinGW tree (`C:\programs\mingw64\include` and `…\lib`), or point GCC at them with environment variables:

   ```powershell
   setx C_INCLUDE_PATH "C:\programs\bdwgc-8.2.12\include"
   setx LIBRARY_PATH   "C:\programs\bdwgc-8.2.12\lib"
   ```

Check that GCC finds the library:

```powershell
gcc -print-file-name=libgc.a
# should print a full path, not just "libgc.a"
```

### 4. Tell Nabu where GCC is

Nabu looks for `gcc.exe` in this order:

1. the system property `nabu.gcc` (full path to `gcc.exe`)
2. the environment variable `NABU_GCC`
3. the directory in the system property `nabu.toolchain.bin` or the environment variable `NABU_TOOLCHAIN_BIN`
4. the `PATH`
5. a few common locations: `C:\mingw64\bin`, `C:\msys64\mingw64\bin`, `C:\msys64\ucrt64\bin`, `C:\ProgramData\mingw64\bin`

The simplest setup is to set `NABU_TOOLCHAIN_BIN`:

```powershell
setx NABU_TOOLCHAIN_BIN "C:\programs\mingw64\bin"
```

When Nabu finds a GCC on Windows, it targets `x86_64-w64-windows-gnu` automatically. Without one it falls back to LLVM's default MSVC target, and linking fails with the MSVC error described above.

### 5. Running the executable

The executables link the MinGW runtime statically, except for **`libwinpthread-1.dll`**. To run an executable outside the build, either put the MinGW `bin` directory on the `PATH`, or copy `libwinpthread-1.dll` from there next to the `.exe`.

## Linux x64 (not tested)

The code links with the `gcc` found on the `PATH`. The `NABU_*` variables above only apply on Windows.

1. Install a JDK 24+ and Maven.
2. Install GCC and the Boehm GC development package, for example:
   - Debian/Ubuntu: `sudo apt install build-essential libgc-dev`
   - Fedora: `sudo dnf install gcc gc-devel`
3. Check: `gcc --version` and `gcc -print-file-name=libgc.so` (should print a full path).

The link command adds `-lgc -lpthread` for `--gc=BOEHM`.

## macOS (not tested)

The code links with the `clang` found on the `PATH`.

1. Install a JDK 24+ and Maven.
2. Install the Xcode command line tools (`xcode-select --install`) for `clang`.
3. Install the Boehm GC, for example with Homebrew: `brew install bdw-gc`.
4. Homebrew's prefix (`/opt/homebrew` on Apple Silicon) is not on `clang`'s default search path. Export it before building:

   ```bash
   export CPATH="$(brew --prefix bdw-gc)/include"
   export LIBRARY_PATH="$(brew --prefix bdw-gc)/lib"
   ```

## Building Nabu with the LLVM backend

The LLVM backend is a plugin. Building Nabu installs it into your user plugin directory, where the compiler picks it up:

```bash
git clone https://github.com/potjerodekool/nabu.git
cd nabu
mvn install -DskipTests -Dmaven.javadoc.skip=true
```

The `native-backend` module copies its jar to `~/.nabu/plugins/native-backend.jar` (`%USERPROFILE%\.nabu\plugins` on Windows). The C runtime sources are bundled inside that jar, so you do not need the Nabu source tree to build native programs. To use a modified runtime instead, set the system property `nabu.runtime` or the environment variable `NABU_RUNTIME_SRC` to the path of your `nabu_runtime.c`; `jcl_lite.c` is taken from the same directory.

<div class="callout callout-note" markdown="1">
If `mvn install` fails in the `native-backend` module with a "file in use" error on `native-backend.jar`, a running Maven daemon still holds the old plugin jar open. Stop it (`mvnd --stop`) and build again with plain Maven.
</div>

## Checking the installation

Compile a small program to a native executable. With this `Hello.java` in `src/main/java`:

```java
public class Hello {
    public static void main(String[] args) {
        System.out.println("Hello " + (args.length > 0 ? args[0] : "native world"));
    }
}
```

and the `nabu-maven-plugin` configured with the LLVM backend:

```xml
<plugin>
    <groupId>io.github.potjerodekool</groupId>
    <artifactId>nabu-maven-plugin</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <configuration>
        <compilerArgs>
            <arg>--backend=LLVM</arg>
            <arg>--gc=BOEHM</arg>
        </compilerArgs>
    </configuration>
    <executions>
        <execution>
            <goals>
                <goal>compile</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

run `mvn package`. The build writes `Hello.ll` (LLVM IR), object files (`Hello.o`, `Hello.1.o`, …) and `Hello.exe` (no extension on Linux/macOS) to `target/classes`. Run the executable:

```powershell
.\target\classes\Hello.exe Ada
# Hello Ada
```

Some things to expect:

- **The JDK is compiled from bytecode.** The backend follows every call from your code and compiles the JDK methods it reaches, and the same goes for libraries on the classpath. You do not configure anything for this.
- **The build takes a while.** Even for a small program the backend compiles about 2,300 JDK classes (15,700 methods), and LLVM then optimizes all of it, in parallel parts. On the verification machine (8 cores) a small program (the integration-test programs) takes about 30 seconds and produces a 13 MB executable.
- **The build prints a summary line per kind of warning**, for example about static fields of classes outside the compiled closure; the details are in `<name>.warnings.txt` next to the executable. They are informational. Functions that could not be compiled at all are listed in `<name>.unresolved.txt` next to the executable; the program only fails if it actually calls one of them.

## Next steps

- [LLVM backend](/backends/llvm/): what the backend supports and its compiler options.
- [Example: a picocli command-line app](/examples/picocli-demo/): one app built as LLVM, JVM and GraalVM executables.
- [Troubleshooting](/backends/llvm/troubleshooting/): linker errors, crashes and how to debug them.

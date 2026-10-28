# Third-party notices

Nabu is under the Apache License 2.0 (`LICENSE`). This file lists what else is in what Nabu ships or links into
the executables it builds, with the license of each. The OpenJDK, which ends up in every executable, is described in
`NOTICE`.

The list covers the distribution of `nabu-native-image` (`native-image/target/dist`) and the backend jar it contains.
The Maven plugin, the daemon and the other modules were not reviewed separately.

## In the distribution: `lib/*.jar`

| Component | Version | License |
|---|---|---|
| ANTLR 4 (tool and runtime) | 4.13.2 | BSD-3-Clause |
| ANTLR 3 runtime | 3.5.3 | BSD |
| StringTemplate 4 | 4.3.4 | BSD |
| ASM (core, tree, analysis, util) | 9.8 | BSD-3-Clause |
| ICU4J | 72.1 | Unicode/ICU License |
| abego TreeLayout | 1.0.3 | BSD-3-Clause |

ICU4J, StringTemplate, the ANTLR 3 runtime and TreeLayout come with the ANTLR tool. The licenses are those that the
POM of each artifact declares.

## Inside `native-backend-*.jar` (shaded, platform specific)

The jar contains the LLVM libraries of its platform; the jar of this build is for `windows-x86_64`.

| Component | Version | License |
|---|---|---|
| JavaCPP (`org.bytedeco:javacpp`) | 1.5.13 | Apache-2.0, or GPL-2.0, or GPL-2.0 with the Classpath Exception (the user chooses; Nabu uses it under Apache-2.0) |
| JavaCPP Presets for LLVM (`org.bytedeco:llvm`) | 21.1.8-1.5.13 | the same three, for the Java bindings |
| LLVM and Clang (libraries, headers) | 21.1.8 | Apache-2.0 with LLVM Exceptions (<https://llvm.org/LICENSE.txt>) |
| ASM | 9.8 | BSD-3-Clause |
| Microsoft Visual C++ runtime (`msvcp140.dll`, `vcruntime140.dll`, `ucrtbase.dll`, `api-ms-win-*.dll` and others) | | Microsoft's terms for redistributable code |

The Microsoft runtime files are in the JavaCPP artifact for Windows and are shaded into the backend jar with it. Their
terms are not those of the other components: **check them before the backend jar is distributed** (see the open points).
The LLVM libraries bring along the third-party code that LLVM itself contains; <https://llvm.org/LICENSE.txt> and the
LLVM documentation list it.

## Linked into the executables Nabu builds

The linker of the build machine (MinGW-w64 on Windows, the system compiler elsewhere) links these into every
executable. They come from the toolchain of whoever builds, not from Nabu.

| Component | Version in the build checked | License |
|---|---|---|
| Boehm-Demers-Weiser garbage collector (`libgc`) | 8.2.12 | permissive, in the Boehm GC license (the notices must stay with copies; the text is in the `bdwgc` repository) |
| `libwinpthread` (MinGW-w64), a DLL next to the executable unless `--static` is given | | permissive (MinGW-w64 winpthreads) |
| `libgcc` (with `--static` or when the executable needs `libgcc_s_seh-1.dll`) | | GPL-3.0 with the GCC Runtime Library Exception |
| The C runtime of the platform (Universal CRT on Windows 10 and later, libc elsewhere) | | part of the operating system |

The licenses of these are those the toolchain documents; they were not read from the build checked here (the MinGW
installation does not carry their texts).

## Open points

* Whether the Microsoft files in the backend jar may be redistributed in this form, and under what conditions. A
  platform backend jar without them (the user gets the JavaCPP artifacts from Maven Central instead of from a shaded
  jar) would not redistribute them.
* The full license texts (BSD-3-Clause, the Unicode/ICU License, the Boehm GC license, Apache-2.0 with LLVM Exceptions)
  are named here, not copied. A binary distribution of BSD-licensed code has to reproduce the copyright notice and the
  license text; the distribution does not carry them yet.
* The other modules (Maven plugin, daemon, Java backend, JPA support) were not reviewed.
* Nothing here is legal advice.

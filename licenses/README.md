# Licenses of the third-party components

The license texts of the components listed in `THIRD-PARTY-NOTICES.md`. The texts are the upstream files, not rewritten;
the table says where each one came from.

| File | Component | Source of the text |
|---|---|---|
| `ANTLR-4-BSD-3-Clause.txt` | ANTLR 4.13.2 (tool, runtime) | `LICENSE.txt` at tag 4.13.2, <https://github.com/antlr/antlr4> |
| `ANTLR-3-BSD.txt` | ANTLR 3.5.3 runtime | `tool/LICENSE.txt`, <https://github.com/antlr/antlr3> (master) |
| `StringTemplate-4-BSD.txt` | StringTemplate 4.3.4 | `LICENSE.txt`, <https://github.com/antlr/stringtemplate4> (master) |
| `ASM-BSD-3-Clause.txt` | ASM 9.8 | `LICENSE.txt`, <https://gitlab.ow2.org/asm/asm> (master) |
| `abego-TreeLayout-BSD-3-Clause.txt` | abego TreeLayout 1.0.3 | `LICENSE`, <https://github.com/abego/treelayout> (master) |
| `ICU4J-Unicode-License.txt` | ICU4J 72.1 | the file `LICENSE` inside `icu4j-72.1.jar` |
| `LLVM-Apache-2.0-with-LLVM-exception.txt` | LLVM and Clang 21.1.8 | `llvm/LICENSE.TXT` at tag llvmorg-21.1.8, <https://github.com/llvm/llvm-project> |
| `JavaCPP-Apache-2.0-or-GPL-2.0-with-Classpath-Exception.txt` | JavaCPP and the JavaCPP Presets for LLVM 1.5.13 | `LICENSE.txt` at tag 1.5.13, <https://github.com/bytedeco/javacpp> |
| `Boehm-GC-8.2.12-license.txt` | Boehm-Demers-Weiser GC 8.2.12 | the section "Copyright & Warranty" of `README.md` at tag v8.2.12, <https://github.com/ivmai/bdwgc> |
| `MinGW-w64-winpthreads.txt` | winpthreads of MinGW-w64 | `mingw-w64-libraries/winpthreads/COPYING`, <https://github.com/mingw-w64/mingw-w64> (master) |
| `GCC-Runtime-Library-Exception-3.1.txt`, `GPL-3.0.txt` | libgcc (GCC Runtime Library Exception and the GPL it refers to) | `COPYING.RUNTIME` and `COPYING3`, <https://github.com/gcc-mirror/gcc> (master) |

The OpenJDK license (GPLv2 with the Classpath Exception) is not here: it is named in `NOTICE`, which says what it means
for the executables Nabu builds.

Nabu does not ship the Microsoft Visual C++ runtime files that the JavaCPP artifact for Windows carries, so there is no
text for them here (see "Inside `native-backend-*.jar`" in `THIRD-PARTY-NOTICES.md`).

Where the table says master, the file was fetched in October 2026 and may have changed since; the versions of the
components are those of the build described in `THIRD-PARTY-NOTICES.md`.

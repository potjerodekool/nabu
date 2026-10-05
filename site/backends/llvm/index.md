---
layout: page
title: LLVM Backend
---

The **LLVM backend** is an experimental **native backend** that compiles the Nabu IR to native code via LLVM, with a small C runtime library.

<div class="callout callout-warning">
<b>Experimental / WIP.</b> This backend is exploratory and duplicates emission logic. It should not be relied upon for production use.
</div>

## Toolchain

The backend uses the **Bytedeco LLVM** JavaCPP bindings (`org.bytedeco.llvm`). A static initializer enables all LLVM targets, MC, ASM parsers, and printers.

## Location

The backend lives in `native-backend/src/main/java/io/github/potjerodekool/nabu/compiler/backend/native_llvm/`.

## Outputs

`NativeLLVMBackend` produces three outputs:

1. **`.ll`** — LLVM IR text
2. **`.o`** — an object file
3. **A linked executable** — only when a `main` function exists

Supported platforms: Windows x64, Linux x64, macOS x64/ARM64.

## Architecture

- **`LLVMModuleEmitter`** — orchestrates emission: globals → function signatures → function bodies.
- **`FunctionEmitter`** — declares signatures (`LLVMAddFunction`; externals get `ExternalLinkage` with no body). `emitBody()` pre-creates all basic blocks (for forward references), maps block labels to `LLVMBasicBlockRef`, pre-scans `TryCatchRegion` instructions into a `tryHandlerMap`, sets a personality function (`@__gcc_personality_v0`) when try regions exist, and emits landing pads that call `@nabu_catch`.
- **`InstructionEmitter`** — maps IR instructions to LLVM builder calls. Calls inside a try region become `invoke` with a continuation-block split.
- **`TypeMapper`** — maps `IRType` → `LLVMTypeRef`.
- **`GlobalEmitter`** — globals and string globals (`[N x i8]`).
- **`ConstantResolver`** — constants → `LLVMValueRef`, with a string-dedup cache.
- **`Linker`** — per-platform linking: Windows `gcc` (MinGW/SEH), macOS `clang`, Linux `gcc`.

## C runtime

The `runtime/` module provides a small C runtime (`nabu_runtime.c` / `.h` + `CMakeLists.txt`) for:

- Runtime type info (`nabu_object`, `nabu_type_info` structs)
- `nabu_instanceof` — runtime instance-of checks
- `nabu_throw` / `nabu_catch` — exception handling via de Itanium ABI (`_Unwind_Exception` + een eigen LSDA-personality voor Windows-SEH en ELF)

### Exception handling per platform

De backend emitteert `personality` = het symbool **`nabu_seh_personality`** (niet
`__gcc_personality_v0`) en levert daarvoor zelf de functie-declaratie mee in het
geëmmitteerde IR. De C-runtime implementeert die routine: `nabu_seh_personality`
leest de LSDA uit `.xdata`/`.gcc_except_table` en installeert het landingpad;
`nabu_itanium_personality` doet hetzelfde voor de ELF/Itanium-variant.

Een *niet-overeenkomende* catch-clause mag na de installatie van het landingpad
niet door `_Unwind_Resume` lopen: op Windows x64 SEH springt dat terug in
hetzelfde landingspad, wordt de personality niet opnieuw geraadpleegd en
`nabu_catch`/`nabu_resume` loopen oneindig. `nabu_resume` geeft daarom op
`_WIN32` een verse `nabu_throw` (nieuwe `_Unwind_RaiseException` vanaf de
landingspad-IP, dus het frame ligt buiten alle call-site-ranges en de zoektocht
vervolgt in een buitenliggend frame). Vindt niemand een handler, dan volgt
`abort()` met `nabu: onbehandelde exception` — hetzelfde gedrag als op de
Unix-paden. De type-filtering zelf gebeurt in de IR in `nabu_can_catch`, niet in
de personality.

| Platform | Status |
| --- | --- |
| Linux x64 (`gcc`) | ondersteund — Itanium-ABI |
| macOS x64/ARM64 (`clang`) | ondersteund — Itanium-ABI |
| Windows x64 MinGW/SEH (`gcc`) | ondersteund — eigen SEH-personality in `runtime/nabu_runtime.c` |
| Windows x64 **MSVC** | **niet ondersteund** |

MSVC wordt expliciet geweigerd: daar bestaat geen DWARF/LSDA-unwind en het
gebruikt x64 SEH *scope tables* met `__C_specific_handler` in plaats van het
DWARF-formaat dat deze backend gebruikt. Ondersteuning zou dus een andere
codegen- en unwind-strategie vergen, niet alleen een `#ifdef` in de runtime.
`Linker` meldt dit met een concrete foutmelding in plaats van te linken naar
een exe zonder runtime-symbolen.
- `nabu_monitorenter` / `nabu_monitorexit` — `synchronized` support

The LLVM backend also supports configurable garbage collection strategies — `NONE`, `BOEHM`, and `REFCOUNT`:

| Strategy | Allocation | Header init | Freeing |
| --- | --- | --- | --- |
| `NONE` | stack `alloca` | by the backend | — (frame lives as long as the function) |
| `BOEHM` | `GC_malloc` | by the backend | never — the collector decides |
| `REFCOUNT` | `malloc` | by the backend (counter zeroed) | `nabu_release` when ownership dies |

- **BOEHM** links against `libgc` (`-lgc`). `GC_init` is called once via `@llvm.global_ctors`, before `main` and before any allocation. Every object — including unknown library classes such as `java/lang/Exception` — goes through `GC_malloc`; the runtime only fills the header (`nabu_init_object`). Fields that the backend reads are written explicitly, since `GC_malloc` only guarantees zeroing (the e2e test verifies against a GC double that poisons memory instead).
- **REFCOUNT** uses strong-ownership per allocation: one `nabu_retain` at allocation, one `nabu_release` when the reference dies (slot overwrite or function exit). Objects that escape the frame (return value, field, call argument, throw) are deliberately not counted and therefore leak; object cycles are not collected. See `RefCountEmitter` for the full semantics.
- **NONE** is for tests and small demos: objects live on the stack for the duration of the function.

## Registration

Registered as backend `"LLVM"` in `native-backend/src/main/resources/plugin.xml`, bundled via `maven-shade-plugin`.

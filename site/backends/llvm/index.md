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
- **`Linker`** — per-platform linking: Windows `link.exe`, macOS `clang`, Linux `gcc`.

## C runtime

The `runtime/` module provides a small C runtime (`nabu_runtime.c` / `.h` + `CMakeLists.txt`) for:

- Runtime type info (`nabu_object`, `nabu_type_info` structs)
- `nabu_instanceof` — runtime instance-of checks
- `nabu_throw` / `nabu_catch` — exception handling via the Itanium ABI (`_Unwind_Exception`) or MSVC path on Windows
- `nabu_monitorenter` / `nabu_monitorexit` — `synchronized` support

The LLVM backend also supports configurable garbage collection strategies: `NONE` (stack-alloca), `BOEHM` (`GC_malloc`), and `REFCOUNT` (`malloc`).

## Registration

Registered as backend `"LLVM"` in `native-backend/src/main/resources/plugin.xml`, bundled via `maven-shade-plugin`.

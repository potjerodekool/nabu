---
layout: page
title: ASM Backend
---

The **ASM backend** is the default, production backend. It emits JVM bytecode using the [ASM](https://asm.ow2.io/) library (version 9.8).

## Location

The backend lives in `compiler/src/main/java/io/github/potjerodekool/nabu/compiler/backend/asm/`.

## Architecture

The main classes:

- **`AsmBackend`** — implements the `Backend` interface; receives an `IRModule` and calls `AsmByteCodeEmitter`.
- **`AsmByteCodeEmitter`** — emits a whole `IRModule` to `.class` files. For each function it runs **PhiElimination → Linearizer → FunctionEmitter** (each function gets its own `SlotAllocator`). Uses ASM's `ClassWriter`, `CheckClassAdapter`, and `TraceClassVisitor`.
- **`FunctionEmitter`** — emits one function's instructions to ASM bytecode. SSA temporaries are resolved to JVM local-variable slots via `SlotAllocator`. Uses ASM's `MethodVisitor`, `Label`, `Opcodes`, `Type`, and `Handle`.
- **`JvmSignatureBuilder`** — builds JVM generic signatures from Nabu `TypeMirror`s.

Support classes in `backend/jvm/`:

- **`SlotAllocator`** — allocates parameters first (slot 0 = `this` for instance methods), maps SSA temp names to unique JVM slots, lazily allocating slots.
- **`Linearizer`** — orders blocks and inverts conditions for fall-through.
- **`BytecodeHelper`** — utility helpers.

## Key characteristics

- Class files are emitted at **version 61 (Java 17)** by default (`JavaVersion.MINIMAL_VERSION`), up to `V23` (`MAXIMAL_VERSION`).
- Uses `COMPUTE_MAXS`.
- Output is validated via `CheckClassAdapter`.
- String concatenation is lowered to `invokedynamic` with `StringConcatFactory.makeConcatWithConstants` (modern javac-equivalent).

## Registration

Registered as backend `"ASM"` in `compiler/src/main/resources/plugin.xml`.

---
layout: page
title: Backends
---

A **backend** is responsible for turning the compiler's intermediate representation (IR) into output — typically JVM bytecode or native code. The Nabu frontend is shared, so the same source can target multiple backends by selecting a different backend at compile time.

## Backend selection

The backend is chosen by a compiler option (`--backend=ASM|JAVA|LLVM`), defaulting to `ASM`. The `BackendManager.createBackend` resolves a `"backend"` extension by name.

## Available backends

| Backend | Name | Output | Status |
|---|---|---|---|
| **ASM** | `ASM` | JVM bytecode (v17+) | Default, production |
| **Java** | `JAVA` | JVM bytecode (ClassFile API) | Experimental |
| **LLVM** | `LLVM` | Native executable | Experimental; verified on Windows x64 |

## The shared IR

All three backends consume the same backend-agnostic `IRModule` produced by the IR generation phase. This is the key to the "write a language once, target multiple backends" design goal. The LLVM backend additionally receives IR modules compiled from JDK bytecode, because a native program has no JVM class library to rely on.

The two JVM backends follow the same structural pipeline:

```
IRModule → PhiElimination → Linearizer → FunctionEmitter (+ SlotAllocator)
```

The LLVM backend maps the IR to LLVM IR instead (`LLVMModuleEmitter`), lets LLVM optimize it, and links the result with a C runtime.

Individual backends:

- [ASM Backend](/backends/asm/) — the default, production backend
- [Java Backend](/backends/java-backend/) — a parallel implementation using the JDK ClassFile API
- [LLVM Backend](/backends/llvm/) — a native backend using LLVM and a C runtime ([installation](/backends/llvm/installation/), [internals](/backends/llvm/internals/), [troubleshooting](/backends/llvm/troubleshooting/))

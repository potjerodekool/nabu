---
layout: page
title: Intermediate Representation
---

The **intermediate representation (IR)** is a backend-agnostic representation of the program, produced by the `IrGeneratingVisitor` from the lowered AST. All backends consume the same `IRModule`, which is what enables targeting multiple backends from a single frontend.

## Location

The IR lives in `compiler/src/main/java/io/github/potjerodekool/nabu/compiler/ir/`.

## Core types

- **`IRModule`** — flags, name, super type, interfaces, sealed class, permitted subclasses, fields, functions, globals (`LinkedHashMap`), annotations, source file/dir, generic signature.
- **`IRGlobal`** — `(name, type, initializer, linkage, constant, ownerType, isStatic)` with factories for `mutable`, `constant`, `internal`, `external`, and `stringLiteral`.
- **`IRFunction`** — name, return type, parameters, blocks, external flag, flags, constructor flag, alloca versions.
- **`IRBasicBlock`** — label, instructions, successors, and termination (`Branch`/`CondBranch`/`Return`).
- **`CFG`** — block map, entry/exit labels.
- **`Dominators`** — idom, dominance tree, dominance frontiers, preorder (iterative).

## Instructions

`IRInstruction` covers: Alloca, AllocaArray, ArrayLength, ArrayLoad, ArrayStore, BinaryOp, Branch, Call, Cast, CondBranch, HeapAlloc, IndirectCall, InstanceOf, Load, MonitorEnter, MonitorExit, Move, Phi, Pop, Return, Store, Throw, TryCatchRegion.

Every instruction has a `result()` and a `location()`.

## Values

`IRValue` covers: ConstBool, ConstClass, ConstFloat, ConstInt, ConstNull, ConstString, ConstUndef, FunctionRef, Named, Temp, and Values.

`IRType` covers: Array, Bool, Float, Function, Int, Ptr, Struct, and Void.

## Building IR

The `IRBuilder` provides fluent IR construction: scoped symbol tables, temp/label/string counters, JLS 5.6.2 binary numeric promotion for `BinaryOp`, alloca/load/store, `Call` (`CallKind`: STATIC/VIRTUAL/INTERFACE/SPECIAL), branches, `Phi`, `Move`, globals/constants, `InstanceOf`, `Throw`, monitors, array operations, and `defaultInitializer`.

## SSA and optimization passes

Located in `compiler/.../backend/ir/`:

- **`SsaBuilder`** — Cytron-style SSA construction: collect allocas, dominance tree + dominance frontiers, phi placement, renaming.
- **`PhiElimination`** — replaces phis with stores in predecessors and loads in the head block.
- **`Linearizer`** — successor order + condition inversion for fall-through.

Optimization passes are in `backend/ir/optimize/`. Each `OptimizationPass` returns a `boolean` indicating whether it changed the IR. The default pipeline runs in order:

```
TypeInference → ConstantFolder → CopyPropagation → GVN → DCE
```

repeated to a fixpoint (max 10 iterations).

- **`GlobalValueNumbering`** (GVN) — per-block value numbers hashing `(opcode, vn(left), vn(right))`.
- **`DeadCodeElimination`** (DCE) — use counts, dead-instruction removal, empty-block removal, fixpoint.
- **`ConstantFolder`** — folds constant `BinaryOp`s.
- **`CopyPropagation`** — replaces `Move` results with their source.
- **`TypeInference`** — fixpoint type propagation.

A `TypeChecker` validates IR types and returns a list of errors.

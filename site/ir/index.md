---
layout: page
title: Intermediate Representation
---

The **intermediate representation (IR)** is a backend-agnostic representation of the program, produced by the `IrGeneratingVisitor` from the lowered AST. All backends consume the same `IRModule`, which is what enables targeting multiple backends from a single frontend.

## Location

The IR lives in the `compiler-api` module, package `io.github.potjerodekool.nabu.backend.ir` (`compiler-api/src/main/java/io/github/potjerodekool/nabu/backend/ir/`). The IR has two producers: `IrGeneratingVisitor` for source code, and `BytecodeMethodLowering` (in `compiler`) for JDK bytecode used by the LLVM backend.

## Core types

- **`IRModule`** — flags, name, super type, interfaces, sealed class, permitted subclasses, fields, functions, globals (`LinkedHashMap`), annotations, source file/dir, generic signature, and the nest host/members (`IRModule.linkNests` links nested classes of one compilation unit, for the JVM's `NestHost`/`NestMembers` attributes).
- **`IRGlobal`** — `(name, type, initializer, linkage, constant, ownerType, isStatic)` with factories for `mutable`, `constant`, `internal`, `external`, and `stringLiteral`.
- **`IRFunction`** — name, return type, parameters, blocks, external flag, flags, constructor flag, annotations, parameter annotations, the default value of an annotation element, and alloca versions.
- **`IRBasicBlock`** — label, instructions, successors, and termination (`Branch`/`CondBranch`/`Return`).
- **`CFG`** — block map, entry/exit labels.
- **`Dominators`** — idom, dominance tree, dominance frontiers, preorder (iterative).

## Instructions

`IRInstruction` covers: Alloca, AllocaArray, ArrayLength, ArrayLoad, ArrayStore, BinaryOp, Branch, Call, Cast, CondBranch, HeapAlloc, IndirectCall, InstanceOf, Load, MonitorEnter, MonitorExit, Move, Phi, Pop, Return, Store, Throw, TryCatchRegion.

Every instruction has a `result()` and a `location()`.

## Values

`IRValue` covers: ConstBool, ConstClass, ConstFloat, ConstInt, ConstNull, ConstString, ConstUndef, FunctionRef, Named, Temp, and Values.

`IRType` covers: Array, Bool, Float, Function, Int, Ptr, Struct, and Void.

`IRType.Int` has a bit width and an `unsigned` flag. `char` is `IRType.CHAR`, an unsigned 16-bit integer, so backends widen it with zero extension (JLS 5.1.2) while `short` (`IRType.I16`) is sign-extended. Reference types are `Ptr` values that carry a JVM descriptor (for example `Ljava/lang/String;`).

`FunctionRef` represents a method reference or lambda. For a lambda converted to a functional interface it also carries the interface, the single abstract method and the captured variables; the JVM backends turn that into `invokedynamic` with `LambdaMetafactory`.

## Building IR

The `IRBuilder` provides fluent IR construction: scoped symbol tables, temp/label/string counters, JLS 5.6.2 binary numeric promotion for `BinaryOp`, alloca/load/store, `Call` (`CallKind`: STATIC/VIRTUAL/INTERFACE/SPECIAL), branches, `Phi`, `Move`, globals/constants, `InstanceOf`, `Throw`, monitors, array operations, and `defaultInitializer`.

`CallKindResolver` picks the call kind. A private method of *another* class (a nestmate) is `VIRTUAL` (or `INTERFACE`), as `javac` does since Java 11; `SPECIAL` is for constructors, `super` calls and private methods of the current class.

## SSA and optimization passes

Located in `compiler-api/.../backend/ir/`:

- **`SsaBuilder`** — Cytron-style SSA construction: collect allocas, dominance tree + dominance frontiers, phi placement, renaming. Allocas of variables captured by a lambda are not promoted, because the lambda creation reads them by name.
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

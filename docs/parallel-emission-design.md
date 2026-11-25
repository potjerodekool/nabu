# Parallel function emission: what it would take, and why it has not been started (2026-11-25)

## Where the time goes (petstore, -Opt NONE, Linux x86, 16 threads; build 154-225 s)

`emitAll` is 90 s. Of that, `bodies` is 74 s, on one thread, in one LLVM context:

| part of `bodies` | time | note |
|---|---|---|
| nullness analysis (`NullnessFlow.analyzeChecked`, 220474 functions) | 4.3 s | pure per function; interval analysis 30 ms (lazy) |
| chunk flush (9 chunks, bitcode copy + object code on a second thread) | 9 x 2.5-3.4 s, partly overlapped | already asynchronous since 856665ab |
| the rest: our emitter + JavaCPP calls + LLVM builder | ~45-50 s | one thread |

JFR (ExecutionSample, the stacks that contain `FunctionEmitter.emitBody`, 967 samples): 68% of the samples have a
JDK frame on top (hash maps, strings), 27% our own code, 5% JavaCPP. JavaCPP time spent in native code is not an
ExecutionSample, so the real native share is larger than the 5%.

## Tried, no gain

- `globalBySuffix` was the biggest single frame in the profile (294 of 967). A negative cache for names that no
  global has, and an index that follows the globals, gave `emitAll` 92.8 s against 89.8 s: noise. (Stashed on
  `parallel-emit` as "globalBySuffix negative cache".)
- Computing the analyses ahead on a pool (PhiElimination, NullnessFlow) can win at most the 4.3 s above (about 3% of
  the build). Not done.

## What real parallelism needs

An LLVM context is not thread safe: types, constants and metadata are uniqued in it. So every worker needs a context
of its own, which means the whole emitter state of the worker is its own too:

1. **The prelude per worker.** Struct types of the classes, the globals as declarations (chunks already treat all
   globals as declarations), function declarations, vtables as declarations. Today this is `index` + `globals` +
   `signatures` + `object model` = ~14 s, so every worker pays it once, in parallel with the others, and holds its
   own copy in memory (the memory work of this session lowered the peak; N copies of the prelude would give part of
   it back).
2. **Globals that the bodies create** (string literals, class slots `.class.slot.N`, cast caches `.cast.cache.N`,
   `.io.cache.N`, `.clinit.once` flags, card table and nursery globals, 19 `LLVMAddGlobal/LLVMAddFunction` sites in
   InstructionEmitter alone) are numbered by a counter and shared by all functions. With workers the names must be
   unique and the definitions must end up in exactly one object (or be `linkonce`/private per object, which costs
   size and de-duplication of strings).
3. **State that is read after the bodies**: `arrayTypeNames()` (array class table), exception table, reflection
   info, `compiledFunctions`/`usedDeclarations` of ChunkedCodegen, debug-info (DIBuilder is per module).
4. **Determinism.** The output must not depend on thread timing (reproducible builds, debuggable diffs): assign
   functions to workers by a fixed partition and merge in a fixed order.
5. **`FunctionEmitter` and `InstructionEmitter` are single-instance** with ~100 fields of per-function and
   per-module state (knownNonNull, blockNonNull, the card/nursery caches, class-init functions, ...). They would
   become one instance per worker, with the per-module state moved to a shared, synchronized or per-worker table.

## Estimate

With 4 workers: prelude 14 s (parallel) + bodies 45-50 s / 4 + flush and merge ~10 s: about 35-40 s instead of
74 s + 14 s, so roughly -45 s of a 154 s build (-30%) at the price of N x prelude memory. It is a multi-day
change in the heart of the emitter, with the failure mode "a program that links but behaves differently", so it
needs the full native IT suite on Linux, macOS and arm64 for every step.

## Recommendation

Do not start it as an unattended change. If compile time matters more than the memory and risk, the cheapest
first step is item 2 + 5 as a refactoring without threads (one `EmitterShared` for the module state, one
`WorkerEmitter` per worker, N = 1), which is verifiable by identical output (same `.ll`) before any thread is
added.

## Status of the refactoring without threads (2026-11-25)

Step 1 is done on `parallel-emit`: the state that the bodies share for the whole module (class-initialiser functions and
flags, class-literal slots, cast/instanceof cache counters, array type table, polymorphic declarations, suffix index of
the globals, ad-hoc externals, the clinit list, the IRFunction -> LLVM function map) lives in `ModuleEmitState`;
`InstructionEmitter` and `FunctionEmitter` get it in their constructor. What stays in the emitters is per function.

How it is checked: an LLVM build of the petstore is **not** deterministic (two builds of the same code differ in 10% of
the function sizes; the order of the modules and the identity-based state vary), so it cannot prove "same output".
A small program is: `Hello` (record, TreeMap, lambda, try/catch; 1646 modules, a 60 MB .ll) built with
`-Dnabu.llvm.chunkThreads=0 -Dnabu.llvm.writeLl=true --keep-work` gives a byte-identical `.ll` and `.o` on every
build. Before and after step 1 the `.ll` and `.o` are identical (cmp). The recipe is in the memory log.

Left for a next step: the statics of `InstructionEmitter` (null-check counters, devirtualisation counters), the
read-only configuration of `FunctionEmitter` (compiledNames, entryPolls, runtimeSymbols), `Tbaa` and the debug-info
builder, which are per module and per context. Then a worker is "an `InstructionEmitter` + `FunctionEmitter` +
context + prelude", which is where threads would come in.

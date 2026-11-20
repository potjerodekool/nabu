# A generational, partly moving collector for the LLVM backend: design

Status: design only (2026-11-20). Nothing of this is built. It is the answer to "how do we get the resident memory of a
server down without losing speed", after everything else was tried.

## Why

The petstore (Spring Boot, JPA, Tomcat) on Windows, PGO build, after 6000 requests: working set 215 MB.

| part | MB |
|---|---|
| GC heap (Boehm, 128 MB initial) | 113-118 |
| executable image | 55 |
| small allocations of the C runtime | 13 |
| the rest (stacks, tables) | about 14 |

The live data of the petstore is 25-55 MB. The heap is 113-118 MB resident because Boehm's collector does not move
objects: between two collections the program allocates into the free holes of the whole heap, so every 4 KB block ends
up holding at least one live object, and a freed block is not given back (measured: idle shrink with
`GC_gcollect_and_unmap` returns 2-4 MB; a standalone libgc test gives the same). Only a moving collector, or a smaller
heap, changes that. A smaller heap costs speed: 64 MB gives 205 MB RSS and up to -5% requests per second; the dirty-bit
(incremental) mode of Boehm, its generational mode, was 13-30% slower. GraalVM native-image has 132 MB RSS and a
generational, moving collector.

Dead ends already measured, so nobody repeats them: idle shrink, the extra byte of Boehm (`n-1`, crashes: an object that
is only referenced by a pointer just past its end is freed), sparse itables (at most 5 MB), ASLR/relocations (not the
cause: the writable data is resident because the collector scans it as roots).

## What the runtime has and lacks

Has:
- one interface between runtime and collector (`runtime/nabu_gc.h`), two implementations (`gc_boehm.c`, `gc_none.c`), a
  Java side that names the allocation symbols the compiled code calls (`gc/GarbageCollector.java`);
- every object of compiled code starts with a header `{ type_info*, monitor, thread }`; arrays have their own layout;
- arrays of numbers are already allocated pointer free (`GC_malloc_atomic`);
- weak and soft references through the collector's disappearing links, finalizers, a hook that pushes extra roots
  (`GC_set_push_other_roots`), scanning of the stacks of continuations.

Lacks:
- **no pointer map per class**: `nabu_type_info` has the name, size, super type and the names and offsets of the fields,
  not which field is a reference. The compiler knows it for every field.
- **no precise roots**: the stacks of threads and fibers are scanned conservatively, as are the registers.
- **no write barrier**: compiled code stores references with a plain store.
- **raw blocks**: the C runtime (`jcl_lite.c` 76 allocations, `nabu_runtime.c`) allocates blocks without a header (field
  infos, reflection tables, lists) that hold pointers to objects.

## The design: a copying nursery in front of the Boehm heap

Do not replace Boehm: put a copying nursery in front of it, and make everything the collector cannot be sure about
**pinned**. Boehm stays the old generation, the fallback for every unknown, and the collector for weak references,
finalizers and fibers. This is the smallest design that can lower the fragmentation, because the objects that die young
(almost all of them: the live set is constant, the rest is garbage) never reach the Boehm heap.

1. **Allocation.** The compiled code allocates from a thread-local bump region (a few MB, one per thread): a pointer
   increment and a limit test inline, the slow path calls the collector. Objects get a header as today. Large objects
   (over about 32 KB) and anything the runtime allocates through `nabu_gc_alloc` go straight to Boehm.
2. **Nursery collection** (when the regions are used up, a stop-the-world pause of the threads at a safepoint-free
   point, as Boehm does now with signals/suspend):
   - roots: stacks and registers (conservative), globals and static fields (conservative scan of the data segment as now),
     old objects on dirty cards (below), the extra roots of the runtime;
   - an object that a conservative root points to is **pinned**: its block stays where it is and is kept alive;
   - every other reachable nursery object is copied to the old generation (`GC_malloc` of the same size, promoted at
     the first survival; an aging step can come later) and all pointers to it that the collector knows precisely are
     updated: pointers inside copied objects (needs the pointer map), and pointers in old objects on dirty cards;
   - a pointer that is **not** found precisely (it sits in a raw runtime block, a stack slot, a register) must have been a
     root to begin with, hence the object is pinned. Precision is therefore an optimization, never a correctness
     requirement: the failure mode of a missing map is "pinned", not "corrupted".
3. **Write barrier.** Every store of a reference into an object marks a card (one byte per 512 B of old space:
   `card[(addr - base) >> 9] = 1`). Compiled code does that in `InstructionEmitter` where it emits stores of reference
   fields and reference array elements; stores into the nursery need none (a test on the destination address, or always
   mark). Expected cost 2-5%, to be measured in stage 1 before anything else is built.
4. **Weak references, finalizers.** Handled by Boehm as today for old objects; a nursery object with a finalizer or a weak
   link is allocated in the old generation directly (rare: `WeakReference`, `Thread`, `Socket`).
5. **Fibers.** Continuation stacks are scanned conservatively as roots, so their referents are pinned: correct, no work.

What this buys: allocation speed (bump pointer instead of `GC_malloc`), far fewer Boehm collections (only old-space
pressure triggers them), and a heap of old live data (25-55 MB) plus a nursery (say 16-32 MB) instead of 128 MB with holes.
Target: working set under 150 MB at the same or better speed.

What it risks: a bug in pinning or in the barrier is silent memory corruption, not a crash at the cause. That is why
stage 0 and 1 are validators first.

## Stages (each one measurable and mergeable on its own)

**Stage 0: pointer maps, no behaviour change.** The compiler emits for every class a bitmap of reference fields in the
type info (and flags arrays of references). Cost: image size (measure). A debug mode (`NABU_GC_VERIFY=1`) walks the whole
Boehm heap after each collection and checks that every reference-looking word in a mapped object is either a map entry
or not a heap pointer: finds the maps that are wrong, before any object moves. Acceptance: petstore and the native ITs run
with the verifier on and report nothing.

**Stage 1: card marking, no moving.** Emit the write barrier, keep Boehm. Measure the cost on the petstore (the budget is
5%) and what fraction of cards are dirty at a collection (the number that decides whether generations pay at all).
If the cost is more than 5% or most cards are dirty, stop here: the design is not worth it.

**Stage 2: nursery without moving.** Thread-local bump regions; objects that survive are *not* copied but the region is
kept (all pinned). Measures the allocation speedup alone and the survival rate per nursery collection (the generational
hypothesis: how many bytes survive).

**Stage 3: copying of unpinned survivors.** Promote to Boehm, update the precisely known pointers. Measure the pinned
fraction (a high one means the conservative roots keep too much) and RSS. This is where the memory is won or not.

**Stage 4 (optional): aging, a bigger old-space policy, giving whole old blocks back.**

## Result of stage 1 (2026-11-20, measured)

`-Dnabu.gc.cardMarking=true` emits a card mark (one byte per 16-byte granule, a wrapping 256 MB window,
`nabu_card_table` in gc_boehm.c) after every store of a reference into an object field or array element;
`NABU_CARD_STATS=1` reports at every mark end how many granules were written and how many of those lie in objects that were
already live at the previous mark end (and at the previous two). Petstore, Windows, 20000 requests of `/pet/2`:

- **Cost of the barrier: none measurable.** 1520 and 1529 requests per second with the barrier against 1502-1528 without;
  the executable is 2.5% bigger (154.9 against 151.1 MB).
- **Old-to-young stores are rare.** Per collection 2.6-2.9 million granules (about 42 MB) were written, but only
  1.6-2.8 MB of them lie in objects that were live at the previous mark end (3-5% of the live set of 40-55 MB), 1.5-2.0 MB
  at the previous two. A nursery collection would scan about 2 MB of old space instead of marking everything.
- A first attempt with cards of 512 bytes said 90% of the old space is dirty; that was false sharing between young and old
  objects in the same 512-byte card of a fragmented heap, which is exactly what a separate nursery removes.

Acceptance of stage 1 (barrier at most 5%, most old cards clean): **passed**. The remembered set is small enough for
generations to pay.

## Open questions that need an answer before stage 2

- **Safepoints.** Boehm stops threads by signals/`SuspendThread`; a thread in the middle of an inline bump allocation has
  a partially built object. Rule: the region is only advanced after the object header is written, and an object
  between the bump and the header write is zero (a zeroed region), so a conservative scan sees nothing. To be verified on
  Windows (`SuspendThread`) and Linux/macOS (signals).
- **Interior pointers.** Compiled code keeps pointers to the data of arrays and strings (`GC_all_interior_pointers`).
  Any root with an interior pointer pins the whole object; the precise updates must handle interior pointers inside
  copied objects (not expected: compiled code holds them in registers/stack only, to be checked in the emitter).
- **Identity hash codes and monitors.** Address based hashes (if any) break on a move: check how `hashCode` of an object
  is derived; the monitor fields are in the header and move with the object.
- **Native code holding object pointers** (JNI-like paths, `Unsafe`): `Unsafe` addresses of objects must pin.
- **`nabu_gc_alloc_pinned`** exists for the runtime's own lists, so the runtime already distinguishes memory that must
  not move.

## Decision requested

Stages 0 and 1 are cheap and reversible and answer whether the whole idea is worth the cost; stage 2 onwards is a large
change to the memory model. The recommended path is to do stage 0 and 1 and decide on the numbers.

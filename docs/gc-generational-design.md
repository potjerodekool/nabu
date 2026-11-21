# A generational, partly moving collector for the LLVM backend: design

Status: stages 0 and 1 built and measured (2026-11-21); stages 2-4 are design only. It is the answer to "how do we get the resident memory of a
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

## Result of stage 0 (2026-11-21, measured)

The compiler emits for every class a list of the offsets of its reference fields (`ref_offsets`, `ref_count` in
`nabu_type_info`, a private constant `.type.refs.<class>` per class; offsets come from the LLVM data layout, so they cannot
differ from the real layout). `NABU_GC_VERIFY=1` walks every reachable object at each mark end and reports words that
point into the live heap but are not in the map of the object's class. Petstore (-Opt NONE), ten endpoint types, 6000
requests, ten collections: 793,542 words checked in the last one, 565,431 of them in a map (71%), the others are numbers
or pointers to non-heap data. Words outside the map that point into live objects: **four fields in total**, all in
`Thread` subclasses (`TaskThread` +16/+72, `LogManager$Cleaner` +16, `TomcatWebServer$1` +16): the thread state and room
words that the C runtime writes (threads-gil-design), not compiled fields. They have to be treated as roots of the
runtime (pinned) in stage 3. No compiled field was missing from a map.

## Result of stage 2, part 1: survival rate (2026-11-21, measured)

`NABU_CARD_STATS=1` also prints `[survival]` at every mark end: the bytes allocated since the previous collection
(`GC_get_total_bytes` delta), and the bytes of objects that are reachable now and were not at the previous mark end (an
object whose first 16-byte granule was not live then; an address that was freed and reused by a new object in between
counts as old, so the figure is a slight underestimate). Petstore, Windows, 15000 requests of `/pet/2`, collections 3-21:

- allocation between two collections 1.6-110 MB (typically about 100 MB), live set 12-31 MB;
- of everything allocated **2.7-3.4% survives** the next collection in the steady state (about 3 MB of 100 MB, 45-50
  thousand objects); the first collections during start-up 5-13%; the few collections that came after only 1.6-15 MB of
  allocation show 6-21% (little time to die).

The generational hypothesis holds for this workload: a nursery of about 100 MB would copy 3 MB per collection, and a
nursery of 16-32 MB somewhat more (less time to die), in the range of 5-10%. Not yet measured: the speed of a bump
allocator against `GC_malloc` (the second half of stage 2).

## Result of stage 2, part 2: allocation speed (2026-11-21)

A standalone test (libgc 8.2.12, 20 million objects of 24-96 bytes, garbage collected as it goes) gives 24 ns per object for
`GC_malloc` and 4.7 ns for a bump pointer including zeroing: at most 19 ns saved per object. In the petstore that is about
1500 objects per request (80 KB per request) = 28 us of 660 us, an upper bound of about 4%. The profile of 2026-11-17 puts the
whole allocation path at 2-7% of the time, and an earlier attempt with thread-local free lists (GC_malloc_kind samples 27 -> 3)
did not change requests per second beyond noise. Expectation for a bump nursery: 0-4% faster, not measured in the compiled
program (that needs the emitter, thread-local regions and a region scan that does not exist yet).

## Assessment before stage 3

- The barrier is free (stage 1), the survival rate is low (stage 2: about 3%), so the design is sound.
- The speed gain is small (above). The reason to continue is only the memory.
- Expected memory: old live set 25-55 MB plus a nursery of 16-32 MB plus the fragmentation of the old space, which now gets
  only the 3% survivors but still frees objects in place. A guess of 95-130 MB for the heap against 113-118 MB today, so
  perhaps 15-30 MB (7-14%) off the 215 MB working set. The 64 MB heap option gives 10 MB for up to -5% speed with no code.
- The cost: copying needs precise updating of every pointer to a moved object, pinned handling of all conservative roots
  (stacks, registers, data segment, the Thread state words found in stage 0, raw runtime blocks, fibers) and the thread
  suspension rules; a mistake is silent corruption.

Recommendation: do not start stage 3 on the strength of 7-14%. It is the user's decision (the mandate says to write down
decisions like this): stage 3 is worth it only if a working set near 150 MB matters more than the risk and the weeks of work.

## Result of stage 3, first measurement: how much would have to be pinned (2026-11-21)

Before building the copying, `NABU_CARD_STATS=1` prints a `[pin]` line per collection: of the young objects (live now, not
live at the previous mark end) how many are pointed to by a word that a precise collector does not know as a reference:
words outside the pointer map of a compiled object, every word of a block that is neither a compiled object nor an array,
the writable sections of the executable (statics), and the stack of the thread that runs the collection (the stacks of
the other threads are not seen, so these are lower bounds). Reference arrays count as precise. Petstore, Windows, 8000-15000
requests of `/pet/2`, collections 3-18:

- **25-45% of the young survivors would be pinned** (by bytes 20-35%; 55-80% in the small collections right after a big
  one). The sources: the stack 50-230 objects, the statics of the image 0-6600 (almost all at the first collections, then
  about 0), typed words outside the map 0-9, and **blocks without a known layout 10,000-24,000**: that is the whole story.
- Those blocks are the problem, not the design of the roots: at the end of a run 200,000 reachable blocks are neither a
  compiled object (type info of the image with a plausible size) nor recognizably an array: 42,000 start with a pointer into
  the image (not accepted as type info: library types, objects smaller than their type info?), 17,600 with a small number,
  78,000 with another value (partly large arrays whose block is rounded to pages, which the array test rejects), 16,000 with
  a heap pointer. The test is a heuristic; the numbers say how far a heap walk without a tag per allocation can get.
- Pinned objects are scattered, so they would keep **15-30 MB of 8 KB pages** alive per collection although the survivors are
  only 3 MB: more than the memory the whole design was meant to win.

Conclusion for stage 3: copying pays only if the pinned fraction is far below 5% and the pinned objects are few per page.
That needs an exact tag for every allocation (object, reference array, array of numbers, runtime block with a trace
function) instead of the guess above, and the 20,000 blocks that today carry no layout would have to get one first
(`nabu_gc_alloc_traced` exists for that). Until then stage 3 would run with a third of the survivors pinned and no memory gain.

### Refined (2026-11-21): the same measurement with exact kinds

The first heuristic mixed up three things. With `NABU_CARD_STATS=1` the runtime now allocates the blocks of `nabu_gc_alloc`
in a kind of their own and the measurement asks Boehm for the kind of every block (`GC_get_kind_and_size`): pointer-free
blocks (strings, arrays of numbers) are skipped, blocks of compiled code (kind 1) are a compiled object (type info of the
image) or an array (every element of a reference array is a precisely known reference), the rest is the runtime's. Same run:

- the large "unknown" group was reference arrays in rounded size classes (a block of 1344 bytes for 128 references) and
  pointer-free blocks: **no layout problem**;
- what is really unexplained in steady state (collections 9-16): **about 15,000 young blocks of 32 bytes per collection whose
  first word is a hidden pointer** (`~ptr`, `FFFFFE6B...`): the entries that `nabu_gc_ref_track` allocates in gc_boehm.c for
  every `WeakReference`/`SoftReference` with a queue (about 15,000 new references per 100 MB of allocation). They are
  conservative blocks and so pin the reference objects they point to. They are our own structures: making them precise
  (the reference slot updated when the object moves) removes most of the pinned objects;
- **blocks of the runtime (`nabu_gc_alloc`, C structs behind jcl objects): 100-3,000 per collection** after start-up (up to
  17,000 during start-up); the stack 70-110 objects; statics of the image about 0 after start-up; typed words outside the
  map 0 (the Thread state words were not in this run).

Rough projection with the entries made precise: 3,000-4,000 of 50,000 young objects pinned (6-8%); with the runtime blocks
also traced about 100 (the stack). The pages kept by those pinned objects are still scattered (200-1,200 pages per
collection in the steady state, 2-10 MB). Copying is not ruled out by this, but it is not free: the remaining work is
(1) precise reference-tracking entries, (2) trace functions for the runtime blocks, (3) the nursery with its collection
itself, (4) stacks of all threads and fibers, (5) validation by poisoning the from-space.

### Second refinement (2026-11-21): what is bookkeeping, and what is still unexplained

Correction of the paragraph above: the 32-byte blocks with a hidden first word are not entries of `nabu_gc_ref_track` (those are
`malloc`ed) but the **disappearing-link entries of Boehm itself** (hidden key, next, hidden object; 30,000-45,000 of them),
and a second large group is **free cells** of the collector's thread-local free lists (first word links to the next cell, the
rest zero). Both are bookkeeping of the collector, not data of the program, and a copying collector in front of Boehm has to
live with them (a weak link to a moved object has to be re-registered; the free cells stay in the old space). They are left
out of the `[pin]` counts now (reported separately).

What is left in the steady state (collections 9-16): 25-45% of the young objects pinned by count, 17-28% by bytes, from
three kinds of holders that are not explained to the last block: (1) blocks of the runtime (`nabu_gc_alloc`, own kind):
100-3,200 per collection, among them 32-byte blocks `[0, p, p, 0]` and 48-byte blocks; (2) blocks of compiled code (kind 1) with
a zero first word and other words that are pointers; (3) the stack, 75-200 objects. After each fix a new class of internal
structure shows up, and each measurement is a 4 minute build. The result so far: **the exact pinned fraction is not known to
better than "a quarter to a third"**, and nothing found says it is below the 5% that copying needs.

Stop point at that moment (superseded by the third refinement below): stage 3 (the copying nursery) is not built. What it would still need is listed above (precise runtime blocks, the
nursery and its collection, all stacks and fibers, validation by poisoning), on top of an unknown pinned fraction. The
instrumentation stays (`NABU_CARD_STATS=1`, off by default) for whoever continues.

### Third refinement (2026-11-21): the pinned fraction is small after all

Two more things in the measurement were wrong, both in how it counted the *targets*:

1. A pinned object that the **runtime itself allocated** (`nabu_gc_alloc`, strings and C structs of jcl_lite such as `s_throw`
   {kind, msg, cls, cause}) is old from the start in this design: runtime allocations go straight to Boehm, only the objects
   and arrays of compiled code live in the nursery. Pointers from runtime blocks to such objects are not pins. (Strings of
   the runtime now have their own pointer-free kind, so they can be told apart.) What counts is whether a runtime block holds
   a pointer to an object *of compiled code*: **0 young objects per collection**, in every collection measured.
2. The big group of "raw" holders was **the disappearing-link hash table of Boehm itself** (blocks of 131,088 and 262,160
   bytes, pointing at its own young link entries). The entries are bookkeeping, not program objects; as targets they are not
   counted now either.

Result, same run (petstore, Windows, 8000 requests of `/pet/2`, collections 8-16):

| | young objects | pinned | % | of bytes | pages of 8 KB kept |
|---|---|---|---|---|---|
| collection 9 | 42,665 | 253 | 0.6% | 1.0% | 147 (1.2 MB) |
| collection 11 | 43,574 | 245 | 0.6% | 5.6% | 155 |
| collection 13 | 10,363 | 186 | 1.8% | 4.4% | 137 |
| collection 15 | 48,632 | 230 | 0.5% | 1.4% | 162 |
| collection 16 | 14,449 | 217 | 1.5% | 2.8% | 143 |

The pins that remain: the stack of the collecting thread (60-150 objects) and about 140 blocks of compiled code with a zero
first word. **0.5-2.7% of the young objects (1-8% of the bytes), 1.0-1.3 MB of nursery pages kept**: below the 5% that
copying needs, and a small price against the 15-30 MB that the fragmented heap holds now. Not yet seen: the stacks of the
other threads (Tomcat's workers while they wait; they hold few objects), the objects pinned by the Thread state words of
stage 0 (not in this run), and anything that appears at other traffic than `/pet/2`.

This changes the earlier assessment: the pinned fraction is **not** a reason to stop. What stays open is the work itself
(nursery and collection, all stacks/fibers, weak links and finalizers of moved objects, validation by poisoning).

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

## Plan for the nursery build (2026-11-21)

Findings from reading the code before starting:

- The allocation front is one swap point: `GarbageCollector.allocateSymbol()/allocateAtomicSymbol()` (LLVMModuleEmitter:1325), already used for `nabu_prof_malloc` and `nabu_dbg_malloc`. A nursery allocator is a third symbol behind `-Dnabu.gc.nursery=true`; default stays `GC_malloc`.
- Finalizers and weak/soft links are registered in only three places in gc_boehm.c (finalizer at ~854, weak link at ~866, reference tracking at ~951) and all three need `GC_base(obj) == obj`. An object in the nursery has no Boehm base, so these registrations cannot happen on nursery objects. Decision: the code generator allocates classes that can get such a registration (subclasses of `java.lang.ref.Reference`, classes with `finalize()`, `Thread` and the like) directly in Boehm, by class, at the allocation site. Nothing in the nursery is ever finalized or weakly linked, and no promotion has to move those.
- Step order, each step flag-gated and validated before the next: (1) nursery region registered as a root region, allocation by bump pointer, fall back to Boehm when full, no collection yet (checks the layout assumptions and the code-generator class filter on the ITs); (2) collection of the nursery: pinning from stacks of all threads and fibers, data segment and runtime roots, precise tracing through `ref_offsets`, promotion by copying into Boehm, pointer updates, from-space poisoned in debug mode; (3) interplay with the Boehm full collection; (4) numbers: RSS and speed against 215 MB and 1500-2100 req/s.

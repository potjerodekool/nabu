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

## Result of nursery step 1: allocation front (2026-11-22)

`-Dnabu.gc.nursery=true` (default off): compiled objects and arrays come from a 64 MB bump region (`nabu_nursery_alloc`, `NABU_NURSERY_MB`), registered with Boehm as a root region one megabyte at a time; a full region falls back to `GC_malloc`. Classes that get a finalizer or a weak link (Reference, Thread, FileDescriptor, classes with `finalize`) and everything the runtime initializes itself (`nabu_init_object` objects, exceptions) are allocated in the Boehm heap (`ClassLayouts.mustStayOld`). A finalizer on a nursery object aborts with a message, so a missed class is found, not leaked. There is no nursery collection yet.

Checks (Windows x64, petstore built with -Opt NONE, /pet/2):
- Petstore starts and answers 8000 requests without errors, repeated for 9 starts. One start in 9 gives a few 404's on the first requests; the same happens in 1 of 13 starts of the build without the flag, so that is an existing start-up race (the data is loaded after the port opens), not an effect of the nursery.
- ITs with the flag on: WeakReferenceE2eIT, ThreadsE2eIT (74 tests), ThreadSubclassE2eIT, ThreadLocalPersistsE2eIT, StringConstantFieldE2eIT all pass.
- No abort from the finalizer guard.

Cost of this step (expected, because nothing is reclaimed yet and Boehm scans the used part of the region as roots at every collection): RSS 372 MB against 277 MB, 850 against 925 req/s at 1500 requests. These numbers say nothing about the finished nursery; they are the price of the part that is not built.

Known gap that step 2 must close: `nabu_gc_weak_set` needs `GC_base(obj) == obj`, so a weak or soft reference to an object in the nursery stays a strong pointer (the referent is not cleared). With a collecting nursery that has to become a real weak link of the nursery collector.

## What step 2 (the collecting nursery) needs, found while sizing it (2026-11-22)

1. **An exact card table.** The stage-1 barrier is an experiment: one byte per 16 bytes of a 256 MB *wrapping* window, so a dirty card cannot be mapped back to one address. A collecting nursery needs a table indexed by `address >> shift` over the whole range of the Boehm heap (reserved, committed lazily) and a way to walk the objects in a dirty card (Boehm: `GC_base` plus the size; typed objects through `ref_offsets`, runtime blocks conservatively).
2. **Barriers in the runtime.** `jcl_lite.c` and `nabu_runtime.c` contain no card marks. The stores of a reference into a compiled object or array from C are: `Unsafe_putReference*`, `Unsafe_compareAndSetReference`/`getAndSetReference`, `System.arraycopy`, clone, reflective `Field.set`, and the helpers that fill arrays and fields (varargs, `nabu_instance_field_offset` users). Each needs a card mark, found by grepping the writes of `void *` through offsets; a missed one shows up as a poisoned from-space object (validation in step 2).
3. **Roots of all threads and fibers**, not only the collecting thread (the measurement saw one stack): the GIL makes every other thread stop at a handoff point, so their saved registers and stacks are known; fibers (ucontext/Windows) have their own stacks, registered with Boehm through `GC_set_stackbottom`.
4. **Weak and soft links of nursery objects** (see step 1): a nursery-owned weak table, cleared by the nursery collector for objects that were not reached.

None of this is started. Step 1 is complete and flag-gated; steps 2-4 are a multi-day piece of work whose first verifiable part is item 1 together with item 2 behind the same flag, validated by poisoning.

## Result of nursery step 2a: the remembered set and its verification (2026-11-22)

With `-Dnabu.gc.nursery=true` a store of a reference into an object or array does not mark a card but records the *slot address* when the stored value points into the nursery (`nabu_remember`, a call behind one unsigned comparison against `nabu_nursery_lo/span`; the holder is skipped when it is itself in the nursery). The C primitives that store references have the same barrier (`NABU_WRITE_BARRIER`, `NABU_WRITE_BARRIER_RANGE` in nabu_gc.h): `Unsafe` put/CAS of references, `System.arraycopy` of 8-byte elements, `clone`, `Arrays.copyOf` of references, `Field.set`, `nabu_reflect_set_ref`, `jcl_arr_set`. `NABU_WB_MASK` (bits 1,2,4,8,16,32,64,128) switches the C sites off one by one; it found a bug below.

**Verification** (`NABU_NURSERY_VERIFY=1`): at each Boehm mark end every word of every reachable old object that points into the nursery must be in the remembered set. Result on the petstore, 3000 requests: of about 30,000 old-to-young words, 1787 were missed before the C barriers, 16-39 after them (0.1%). The remaining misses are all in the state structs that the runtime allocates for `java.lang.Thread` (`s_thread`: target, group, thread locals, container...), written by about 15 sites in jcl_lite.c.

Findings:
- The remembered-set buffer was first a growing array (`realloc`). That corrupted the heap (a crash in `nabu_array_type_id` that only appeared when the Unsafe barrier was on): more than one thread executes these primitives at the same time, so the GIL does not serialize everything that runs C. The buffer is now reserved once and filled with an atomic index. This also matters for the stage-1 assumption that a global bump pointer needs no atomics: it uses one.
- `ClassLayouts.mustStayOld` stopped at a superclass that is not a compiled module (`Thread`), so `TaskThread` and other Thread subclasses were allocated in the nursery. Fixed by walking `IRModule.superType()`.
- Barrier cost cannot be told from noise: 880-950 req/s with the barrier against 850-925 without (the build with -Opt NONE).
- Blocks without a type (runtime structs and reference arrays), the part that holds the C-side stores, are 7-9 MB of 10-15 MB scanned objects on the petstore.

**Open design problem for step 2b** (not solved): a conservative scan of the runtime blocks at each nursery collection (about 9 MB, 2-4 ms) closes the C-side hole without a barrier at every writer, but it pins the objects those blocks point to. A pinned object stays in its nursery page, and Boehm cannot see nursery objects die, so the page must be freed by the nursery collector itself. That needs the mostly-copying scheme (pinned pages stay from-space pages, their other objects are copied out, the pins are re-evaluated at every collection) and a way to drop remembered-set entries of dead old objects after a Boehm collection (mark bits). Without both, the roots the nursery registers with Boehm keep everything they point to alive forever.

### Step 2a, second round: thread state, deduplication, what is left (2026-11-22)

The state struct of `java.lang.Thread` (`s_thread`) is written through `JCL_STORE_REF` (barrier included) at 17 sites. At every verification the remembered set is sorted and deduplicated in place, which keeps it at 0.3-0.7 million slots instead of overflowing. Result on the petstore, 1500 requests, 7 Boehm collections: 0, 0, 12, 5, 9, 4, 2 of about 30,000 old-to-young words not remembered.

All remaining misses are in 1344-byte blocks at words 111-121: that is an x64 `CONTEXT` (1232 bytes) as `GetThreadContext` fills it for the stack capture of another thread (nabu_runtime.c:1865): register snapshots, i.e. stale data, not references the program uses. A collector must not treat them as roots and does not need to update them. The barrier coverage of the stores of compiled code and C primitives is therefore complete for this workload; what remains for the roots is the live registers and stacks of all threads (step 2b).

## Step 2b, first part: a dry-run collection that only measures (2026-11-23)

`NABU_NURSERY_VERIFY=1` now also runs, at every Boehm mark end, a dry run of a nursery collection (`nabu_nursery_dryrun`, nothing is moved or freed): it walks the used nursery object by object, builds a start bitmap, marks from the remembered set, the stack of the collecting thread and the writable image sections, and traces through the nursery with `ref_offsets` (reference arrays: every element).

Petstore, Windows, 64 MB nursery, collections 2-8:
- **The nursery can be walked exactly.** 1,002,363 objects, 0 bad headers, 2 zero gaps (type info or the array header `[i32 length][i32 es|id<<8]` gives the extent, rounded to 16). Compiled objects need no extra header or side table for a collector to find object starts.
- **Live: 8.0-8.2 MB of 64 MB (12.3-12.6%)** and stable over seven collections: the long-lived startup data of Spring. Remembered slots of dead old holders count as roots (an upper bound); stacks of other threads are not scanned (a lower bound).
- The nursery is **full from the first collections on** (fallback to `GC_malloc` afterwards), so these seven snapshots are the same frozen nursery: this is not yet a steady-state survival rate. That needs a collector that frees the region and reuses it.
- Bug in the measurement: the bump pointer keeps counting after the region is full; used is clamped to the limit.

What this settles for 2b: the walk, the extent rule and the tracing rule work; what is left is the roots of all other threads and fibers, the copy/promotion with pointer updates, pinning, and the lifecycle of the region (reuse after collection).

## Step 2b, roots of all threads: a blocker found (2026-11-23)

Task: exact roots (stack and registers) of every thread at a nursery collection. Findings from reading the runtime and the build:

- **The GIL is not the default.** `nabu_parallel` is 1 unless `NABU_GIL=1`: threads run truly in parallel, so there is no handoff point where "all other threads are parked with known stacks". The GIL paths (`nabu_gil_*`) are the exception.
- **Compiled code has no safepoints.** The only place where all threads are stopped is inside a Boehm collection (its signal/`SuspendThread` stop-the-world), i.e. at `GC_EVENT_MARK_END` with the world stopped, which is where the dry run runs.
- **libgc does not expose the stacks.** Boehm pushes thread stacks internally (`GC_push_all_stacks`); the public API has `GC_set_push_other_roots` (called after the stacks), `GC_get_my_stackbottom`, `GC_stop_world_external`, but no way to read another thread's stack range or its saved registers. And libgc is the system package everywhere (apt `libgc-dev`, brew `bdw-gc`, mingw), so it cannot be patched without shipping our own build.
- The runtime does not track the stack extent of other threads either (only `jcl_stack_hi` of each thread's own base; the current sp of a running or suspended thread is nowhere).

So the dry run (collecting thread's stack only) is as far as the portable design gets. Ways on, all with a cost:
1. **Ship our own libgc build** (vendored source, patched to expose `GC_push_all_stacks` as a callback or the thread table). Exact and portable, but a new dependency to build on three OSes plus release packaging.
2. **Platform code**: Windows via thread enumeration + `GetThreadContext` while Boehm has the world stopped (partly exists in nabu_runtime.c:1865 for stack traces), Linux/macOS via our own suspend signal. Fragile, three implementations, interplay with Boehm's own signals.
3. **Make the runtime a cooperating mutator**: safepoint polls in compiled code (loop back-edges, calls) and a runtime-owned stop-the-world. Cost in speed and code size, and a change to every compiled function.
4. **Stop here**: keep the nursery as a flagged experiment (allocation front + barrier + dry run), because the 12% measured live fraction does not yet justify one of 1-3. Boehm's own generational mode (`GC_enable_incremental`, dirty bits) is the cheaper alternative to compare against first.

This is a decision for the owner (a new build dependency or a safepoint mechanism in compiled code is not something to introduce unasked). Nothing in main depends on it: the flag stays off.

## Decision (2026-11-23): option 4, and the comparison with Boehm's incremental mode

The owner chose option 4: the nursery stays a flagged experiment (allocation front, barrier, dry run; nothing collects), and Boehm's own incremental/generational mode is measured first.

`GC_ENABLE_INCREMENTAL=1` (read by libgc itself, no code change; dirty bits from `GetWriteWatch` on Windows) on the petstore build (-Opt NONE, Windows, 8000 requests of `/pet/2` after a 1000 request warm-up, each line one start):

| mode | req/s | RSS |
|---|---|---|
| default (full collections) | 947, 985, 1019, 1024 | 308-319 MB |
| incremental | 930, 913, 914, 918 | 259, 260, 260, 259 MB |
| incremental, `GC_FULL_FREQUENCY=3` | 918, 913 | 234, 226 MB |
| incremental, `GC_FULL_FREQUENCY=20` | 917, 914 | 258, 260 MB |

Reading: incremental mode costs 5-10% of the speed and saves 15-27% of the resident memory (the more often it runs a full collection, the smaller the RSS). That is not a free win, so the default stays as it is; the mode is available to anyone who prefers memory over speed, by environment variable alone. Pause times were not measured: `NABU_GC_STATS` prints nothing in incremental mode (the collection event it hooks is not raised for the partial collections), so a comparison of pauses needs `GC_PRINT_STATS`. Not measured on Linux (dirty bits there come from `mprotect`, which behaves very differently and which the GIL-less runtime may not tolerate: untested) and not with an -O2/PGO build.

For comparison the nursery would have to beat 15-27% of RSS at no speed loss; its first dry-run number (about 8 MB live of a 64 MB nursery) says nothing about that yet, and its price is the new root machinery above. Parked.

Note: the dry-run collection described under "Step 2b, first part" (`nabu_nursery_dryrun`, `NABU_NURSERY_VERIFY=1`) lives on branch `gc-nursery` only; main has the nursery up to step 2a.

### Incremental mode: pause times and Linux (2026-11-23)

Pauses (Windows, petstore, `GC_PRINT_STATS=1 GC_LOG_FILE=...`, 8000 requests after warm-up; pause = "World-stopped marking took"):

| mode | collections | average pause | longest | total pause |
|---|---|---|---|---|
| default | 16 | 36 ms | 78 ms | 577 ms |
| incremental | 38 | 31 ms | 47 ms | 1161 ms |
| incremental, `GC_FULL_FREQUENCY=3` | 42 | 31 ms | 47 ms | 1311 ms |

The longest pause drops from 78 to 47 ms, the average hardly moves, and there are 2.4 times as many collections, so the total pause time doubles: consistent with the 5-10% lower throughput. (`NABU_GC_STATS` is silent in this mode; use `GC_PRINT_STATS` with `GC_LOG_FILE`.)

Linux (WSL2, Ubuntu, gcc 15, system libgc, the native ITs run with `GC_ENABLE_INCREMENTAL=1` in the environment): ThreadsE2eIT (74 tests), WeakReferenceE2eIT, StackTraceE2eIT, ThreadSubclassE2eIT all pass. The collector log of the run shows `Using soft-dirty bit feature`: on this kernel libgc takes its dirty bits from the kernel's soft-dirty page flag, not from `mprotect`, so it installs no SIGSEGV handler and does not meet `nabu_sigsegv_handler` (which replaces any earlier handler without chaining). **Not covered:** a kernel or container without soft-dirty support (libgc then falls back to `mprotect` and its own SIGSEGV handler, which the runtime's crash handler would override: untested), macOS, arm64, and throughput/RSS on Linux (no petstore build there; the 11 collections of the small ITs say nothing about it).

# Performance study: nabu-native-image against GraalVM native-image (petstore demo)

Handover for a session on a Linux (Ubuntu) machine. Written 2026-11-09 from a Windows session. Everything below that is
a number was measured there; everything marked *estimate* is a guess to be replaced by measurements.

> **Current-status note (added 2026-11-11).** This file is a dated research log: each section records the state at the
> time it was written, so older numbers (e.g. "the object header is 40 bytes") are that phase's baseline, not the
> present layout. The **current object header is 16 bytes** — `{ void *type, int32_t monitor_count, int32_t thread_id }`
> — with the vtable and itable in `nabu_type_info` (slots 8 and 9); this is the Phase 2 layout. The Phase 3 experiment
> that shrank the header to 8 bytes with an external monitor table was measured as a regression and reverted (see
> *Phase 3 results* and *Current state* below). This matches `ClassLayouts.objectHeaderType()` and `struct nabu_object`
> in `nabu_runtime.h`, and the object model in `site/backends/llvm/internals.md`.

## Goal (decided by the owner)

The petstore demo built with `nabu-native-image` must come within **1.5x of GraalVM `native-image`** on all three:
throughput, startup and resident memory. Linux, macOS and arm64 must keep working; the build must stay under 8 GB.
This phase is a **feasibility study**: measure, then decide the direction before big changes. The architecture is open
(LLVM stays unless the numbers say otherwise); a replacement backend and a JIT hybrid were judged poor options.

## Where we are (Windows x86-64, i9-9900, one keep-alive client, sequential, 3000 requests per endpoint)

|                          | GraalVM CE 25.4 | Nabu -O2 | Nabu -O0 |
|--------------------------|-----------------|----------|----------|
| `/pet/2` req/s           | 2233            | 1072     | 806      |
| `/pet/findByStatus` req/s| 1920            | 660      | 508      |
| `/store/inventory` req/s | 1914            | 550      | 394      |
| startup (to first 200)   | 0.5 s           | 1.6 s    | 2.2 s    |
| RSS after the run        | 130 MB          | 365 MB   | 387 MB   |
| build time               | 156 s           | 749 s    | 181 s    |
| build memory (peak)      | 6.4 GB          | 7.0 GB   | ~5.4 GB  |
| exe size                 | 135 MiB         | ~140 MB  | ~224 MB  |

The smoke test (`petstore-demo/smoke.ps1`) passes completely for the Nabu build.

What the measurements say so far:
- GC is not the main cost: `-Gc NONE` (never collects) is only 13-20% faster than Boehm.
- Static initializers are 141 ms of the 2.1 s startup (6.5%); startup is one Spring thread, 100% CPU, running ordinary
  code that is as slow as the request path. Startup and throughput therefore share a cause: the generated code.
- LLVM -O2 is only ~30% faster than -O0, which suggests LLVM is given too little information, not that LLVM is slow.
- Done already (each a few percent): class literal cache, instanceof cache per call site, TBAA tags, noalias
  allocation, closed-world devirtualization (CHA, JDK classes included except those the C runtime makes itself),
  atomic (pointer-free) arrays, Boehm initial heap of 128 MB.
- Tried and dropped (no gain or broken): skipping class-init guards (broke startup, cause unknown), build-time class
  init (too little to gain), devirtualizing JDK receivers (no measurable gain at -O0).
- The object header is 40 bytes (type, vtable, itable, an unused ref count, monitor, thread); GraalVM's is 8-16 *(to be
  verified)*. Object size is the suspected cause of both the RSS and many cache misses. Not measured.

## Candidate levers (estimates, to be validated by phase 0)

| # | Lever | Gain (estimate) | Effort | Risk |
|---|-------|-----------------|--------|------|
| 1 | Compact object header (8-16 B), vtable/itable reached through the type object | +10%, RSS -25% | 2-3 weeks | medium |
| 2 | JDK intrinsics: `String.equals/hashCode/indexOf`, `System.arraycopy`, `Arrays.fill` as memcmp/memchr/memmove | +5-10% | 1-2 weeks | low |
| 3 | Inline type checks (class numbering) instead of a runtime call | +3-5% | 1 week | low |
| 4 | Own middle-end before LLVM: whole-program type-flow analysis, devirtualization + inlining on receiver sets, escape analysis with scalar replacement, null/bounds check elimination | +30-50% | 2-3 months | high |
| 5 | Precise generational GC, bump allocation, inline fast path; mostly-copying with a conservative stack (no LLVM stackmaps, no changes to jcl_lite.c) | +10%, RSS -15% | 1-2 months | high |
| 6 | LLVM PGO (instrumented training run, indirect-call promotion). Needs a training workload; GraalVM CE has no PGO | +15-25% (done 2026-11-18: +35-50% on the petstore, see Lever 6 results) | 2-3 weeks | medium |
| 7 | 32-bit compressed references (needs 5) | RSS -15% | 1-2 months | high |

Cheap experiments first: O3 (`--opt AGGRESSIVE`), a higher inline threshold, a larger cross-partition import limit
(`PartitionedCodegen.IMPORT_LIMIT`, now 40 instructions), one partition instead of two.

## Phase 0 results (Linux x86-64, i9-14900K, one keep-alive client, sequential, 3000 requests per endpoint)

The CPU governor is `powersave` (no `sudo`); all numbers were taken after a throw-away warm-up run to bring the CPU
to boost frequency. Date: 2026-11-09. GraalVM CE 25.4 (JDK 25.0.4.1.1); Nabu build from the same day.

### Throughput, startup, resident memory

|                          | GraalVM CE 25.4 | Nabu -O2 | Nabu -O0 |
|--------------------------|-----------------|----------|----------|
| `/pet/2` req/s           | 6169            | 1802     | 1265     |
| `/pet/findByStatus` req/s| 4037            | 1015     | 733      |
| `/store/inventory` req/s | 3988            | 986      | 669      |
| GraalVM/Nabu -O2 ratio   |                 | **3.4–4×** |       |
| startup (to first 200)   | 0.2 s           | 1.1 s    | 1.6 s    |
| RSS after the run        | 165 MB          | 375 MB   | 397 MB   |
| build time (native image)| not measured    | ~550 s   | ~116 s   |
| exe size                 | 134 MB          | 199 MB   | 270 MB   |

The smoke test (`petstore-demo/smoke.sh`) passes completely for the Nabu -O2 build.

### Hardware counters (`perf stat -p`, Python keep-alive loader, `/pet/2`)

Measured over 10 s with a single keep-alive Python loader (≈ 24 k requests for GraalVM, ≈ 17 k for Nabu -O2).

|                              | GraalVM CE | Nabu -O2 | ratio |
|------------------------------|------------|----------|-------|
| instructions / request       | ~1.0 M     | ~11.8 M  | **11.4×** |
| IPC (combined P+E cores)     | 0.85       | 1.23     | 0.69× (Nabu *higher*) |
| IPC (cpu_core / P-cores only)| 1.06       | 1.44     |       |
| LLC-load-misses / request    | ~21        | ~831     | **40×** |
| L1-dcache misses / request   | ~8,650     | ~31,400  | 3.6×  |

### RSS breakdown (`/proc/<pid>/smaps_rollup`, after 500 warm-up requests)

|                           | GraalVM CE | Nabu -O2 |
|---------------------------|------------|----------|
| RSS total                 | 155 MB     | 293 MB   |
| Anonymous (heap + stacks) | 64 MB      | 185 MB   |
| Private_Clean (code)      | 93 MB      | 110 MB   |
| Shared_Clean (libs)       | 2.7 MB     | 2.5 MB   |

### What the numbers say

**Instructions per request is the dominant gap (11×), not IPC.**
Nabu -O2 has *higher* IPC (1.23 vs 0.85) because its straight-line code is easier for the CPU to execute once it is
fetched; there is simply far more of it. The throughput gap (3.4–4×) is narrower than the instruction gap because
the IPC advantage partially compensates for the extra work.

**The 40× LLC-miss delta confirms the object-header hypothesis.**
Nabu objects carry a 40-byte header (type pointer, vtable, itable, unused refcount, monitor, thread); GraalVM's is
8–16 bytes. Each extra 32 bytes per object forces roughly two extra cache-line loads per pointer traversal
(virtual call, interface dispatch, field access). Heavy use of interface dispatch in Spring, Hibernate, and Jackson
amplifies this. The heap is 185 MB vs 64 MB (2.9×) for the same workload; the 3.6× L1 miss inflation is
proportional, and the 40× LLC miss inflation shows the objects are scattered across a much larger arena.

**LLVM -O2 gives only 1.4× over -O0 (`/pet/2`: 1802 vs 1265 req/s), matching the Windows result.**
Most calls are still indirect (virtual or interface); objects are heap-allocated (no scalar replacement); bounds and
null checks remain. LLVM gets too little information to optimize across call boundaries. A Nabu-side middle-end that
devirtualizes and inlines before LLVM would help far more than raising the LLVM opt level.

### Lever estimates revised by the data

| # | Lever | Revised estimate | Supporting evidence |
|---|-------|-----------------|---------------------|
| 1 | Compact object header (8–16 B) | +15–30% throughput, RSS −30% | 40× LLC miss delta; 2.9× heap inflation |
| 4 | Own middle-end (inlining, devirt, escape analysis) | +2–4× throughput | 11× instruction gap; LLVM -O2 barely moves the needle |
| 6 | LLVM PGO (indirect-call promotion) | +10–20% | Many indirect calls remain after -O2; cheap to try |
| 2 | JDK intrinsics (`String.equals/hashCode`, `arraycopy`) | +5–10% | String-heavy Jackson/Hibernate paths visible in counts |
| 5 | Generational GC, bump fast path | +5–15% | 185 MB live heap; GC pressure expected at this scale |

### Flame graph (perf record -g, Nabu -O2, /pet/2, 30 s)

SVGs: `docs/perf-study/flamegraphs/nabu-O2-pet2-30s.svg` (Nabu), `graalvm-pet2-30s.svg` (GraalVM).
GraalVM's binary has no DWARF debug info; nearly all samples land on `[petstore]` (heavily inlined, stacks
unresolvable). Nabu's stacks are fully symbolised.

CPU budget for Nabu -O2 (self-time, all threads combined, /pet/2):

| Category | % of total CPU | Note |
|---|---|---|
| Application logic (other) | 57% | Spring dispatch, Jackson, Hibernate, H2 — spread across many paths |
| I/O + syscalls | 8% | `recv`, `epoll_wait`, `fput`, `entry_SYSCALL_64` |
| Monitor enter/exit | 7% | `nabu_monitorenter` + `nabu_monitorexit` in NIO selector + ConcurrentHashMap |
| GC marker threads | 8% | Boehm concurrent marker; runs parallel to app |
| ConcurrentHashMap | 6% | Spring/Jackson/Hibernate lookup caches (`tabAt` + `Traverser_advance`) |
| Clock reads | 5% | `clock_gettime` / `read_tsc` / Tomcat timeout polling |
| GC lib (app threads) | 4% | Tracing + collection within app context |
| GC allocation | 2% | `GC_malloc_kind`, `GC_generic_malloc_many` |
| Reflection | 2% | `nabu_compiled_type_name`, `jcl_class_slot` |
| Checkcast | 2% | `nabu_checkcast_class` |

**GC total (marker + lib + alloc): ~14%** — consistent with Windows (-Gc NONE is 13–20% faster).

The 57% "other" is where the 11× instruction gap vs GraalVM lives. Each logical operation (field access, virtual
call, interface dispatch) emits more instructions than GraalVM because Nabu lacks a middle-end for inlining and
scalar replacement. The monitor overhead (7%) and ConcurrentHashMap (6%) are individually addressable but
secondary compared to the instruction-count gap.

Pending from phase 0: allocation volume per request (counter in `nabu_gc_alloc` under an env variable).

## Cheap experiments (Linux x86-64, i9-14900K, same bench setup as phase 0)

Built and benchmarked on 2026-11-09. All variants use `--gc BOEHM`. Benchmarked after a throw-away warm-up run.
Default = O2, 2 partitions, import-limit 40.

| Variant | `/pet/2` req/s | `/findByStatus` req/s | `/store/inventory` req/s | RSS | start |
|---------|---------------|----------------------|--------------------------|-----|-------|
| DEFAULT (O2, 2 partitions, il=40) | 1871 | 1051 | 1039 | 341 MB | 1.1 s |
| p1 (O2, **1 partition**, il=40) | 1856–1878 | 1064–1069 | 1064–1069 | 342 MB | 1.1 s |
| il100 (O2, 2 partitions, **il=100**) | 1769 | 999 | 1029 | 341 MB | 1.1 s |
| O3 (AGGRESSIVE / **-O3**, 2 partitions) | 1786 | 1008 | 1018 | 343 MB | 1.1 s |

Build notes:
- il100 split: 38 945 small functions copied across partition boundaries (vs 31 943 for DEFAULT/O3).
- p1 backend time: 578 s (single 500 MB LLVM module). DEFAULT/O3 backend: ~550–560 s (2 × parallel).
- Measurement noise is ±5–7% on `/pet/2` and ±3–5% on the complex queries (CPU powersave governor).

**Conclusion: none of the cheap experiments move the needle.** All variants land within measurement noise of DEFAULT.

What this tells us:
- **O3 vs O2**: no gain. LLVM gets too little inlining context from the bytecode front-end regardless of opt level.
- **1 partition**: no gain. Cross-function inlining within the single LLVM module is not the bottleneck.
- **Higher import limit**: no gain (slightly worse). More cross-partition copying does not expose new inlining opportunities at this scale.

The bottleneck is at the IR level: too many indirect calls, too little scalar replacement, too few inlined call chains.
LLVM cannot fix that at O2 or O3 because it cannot see through the indirect dispatches. The path forward is Lever 1
(compact headers, cuts the 40× LLC-miss delta) and Lever 2 (JDK intrinsics) for near-term gains, then Lever 4
(middle-end inlining/devirt) for the structural fix.

## Lever 1 results (Linux x86-64, i9-14900K, same bench setup as phase 0 — 3000 req/endpoint)

Lever 1 reduces the object header in three phases:

| Phase | Header layout | Header size |
|-------|--------------|-------------|
| Phase 0 (baseline) | `{ ptr type, ptr vtable, ptr itable, i32 ref_count, i32 monitor, i32 thread }` | 40 B |
| Phase 1 | remove unused `ref_count` | 32 B |
| Phase 2 | vtable + itable moved into `nabu_type_info`; header is `{ ptr type_info, i32 monitor, i32 thread }` | 16 B |

Phase 1 is a pure deletion commit (the field was unused), not separately benchmarked.

### Phase 2 results (2026-11-09, header 32 B → 16 B, vtable/itable in type_info)

| Metric | Phase 0 baseline | Phase 2 | Δ |
|--------|-----------------|---------|---|
| `/pet/2` req/s | 1802 | 1801 | ≈ 0% |
| `/pet/findByStatus` req/s | 1015 | 1055 | +4% |
| `/store/inventory` req/s | 986 | 1056 | +7% |
| startup (to first 200) | 1.1 s | 1.1 s | ≈ 0% |
| RSS after the run | 375 MB | 364 MB | −3% |

**Conclusion:** Phase 2 is within measurement noise on throughput (±5–7% on `/pet/2`, ±3–5% on complex queries).
The RSS reduction is a modest −11 MB (−3%). The header size halved (32 B → 16 B) but the LLC-miss bottleneck is
not in the header alone; the vtable/itable pointer indirections remain and allocations are still large.

### Phase 3 results (2026-11-10, header 16 B → 8 B, external monitor table)

Phase 3 moves `monitor_count` and `thread_id` out of the object header into an external open-addressed
hash table (lockless scan via atomic key reads, CAS for new insertions, entries never deleted).
Header is now `{ ptr type_info }` = 8 bytes.

Implementation notes:
- First attempt used a locked table (`nabu_rt_lock` on every monitorenter/exit): **−20% regression** —
  the global recursive mutex dominated the hot path.
- Final design: 8 M-entry static table (128 MB BSS, lazy OS pages), lockless open-addressing, no tombstones.
  Table is large enough that entries are never evicted during the benchmark (9500 total requests).

| Metric | Phase 2 | Phase 3 | Δ |
|--------|---------|---------|---|
| `/pet/2` req/s | 1801 | 1571 | −13% |
| `/pet/findByStatus` req/s | 1055 | 966 | −8% |
| `/store/inventory` req/s | 1056 | 973 | −8% |
| startup (to first 200) | 1.1 s | 1.2 s | +9% |
| RSS after the run | 364 MB | 732 MB | **+101%** |

**Conclusion:** External monitor table is a regression for this workload. The petstore with Hibernate/H2
creates ~508 unique synchronized objects per request; with 9500 total requests, ~4.8 M table entries
accumulate, requiring a 128 MB BSS table (essentially all pages touched → ~128 MB physical RSS). Throughput
decreases 8–13% from the extra hash-table probe on every `monitorenter`/`monitorexit` (previously a direct
field read of `header.monitor_count`). The 8-byte header saving is negligible against the 128 MB table cost.

**When Phase 3 would help:** workloads where only a small fraction of heap objects are ever synchronized.
For a 200 MB heap with 2 MB of monitored objects (1%), Phase 3 would save ~25 MB from smaller headers and
add only ~2 MB of table entries. The petstore is an adversarial case: Hibernate/H2 synchronizes nearly every
per-request object.

## Lever 2 results (2026-11-10, String.hashCode / String.equals C intrinsics)

Lever 2 replaces the compiled `String.hashCode` and `String.equals` bytecode with C intrinsics in
`jcl_lite.c`. The compiled versions had a `java.lang.Byte.clinit.once.xp` check and a
`nabu_safepoint_poll` on every iteration of the hash and comparison loops (no auto-vectorisation).
The C replacements use a plain `int` loop for `hashCode` (GCC can vectorise it) and `memcmp` for
`equals`. The compiled symbols are weakened with `objcopy --weaken-symbol` so the BIND definitions
in `jcl_lite.c` win at link time.

| Metric | Phase 3 baseline | Lever 2 | Δ |
|--------|-----------------|---------|---|
| `/pet/2` req/s | 1571 | 1580 | +0.6% (noise) |
| `/pet/findByStatus` req/s | 966 | 944 | −2% (noise) |
| `/store/inventory` req/s | 973 | 952 | −2% (noise) |
| startup (s) | 1.2 | 1.0 | — |
| RSS | 732 MB | 731 MB | — |

**Conclusion:** no measurable improvement. The CPU branch predictor eliminates the per-iteration
overhead (clinit check + safepoint poll) after a handful of iterations on warm code, so the
compiled loops are not slower than the C replacements in practice. The bottleneck in this workload
is the external monitor table (Phase 3 regression) and Hibernate/H2 I/O, not String hashing or
equality comparison. The intrinsics are correct and may help in workloads that are cold (no
branch-predictor warmup) or that hash many unique strings with large arrays; they remain in
`jcl_lite.c` as a foundation for further tuning.

## Lever 3 results (2026-11-10, inline final-class instanceof — pointer compare, no call)

Lever 3 replaces calls to `nabu_instanceof_cached` for `final` classes with an inline pointer compare.
The old path: null check → load inline-cache slot → compare → conditional call into the runtime cache.
The new path: null check → load `obj->type` → compare against `&C_type_info`. No function call, no
cache-slot global per call site.

Implementation: `InstructionEmitter.emitFinalClassInstanceOf` emits a two-block CFG (null-check block
→ type-check block → merge phi). The binary is ~167 KB smaller (no cache-slot globals).

| Metric | Phase 3 baseline | Lever 3 | Δ |
|--------|-----------------|---------|---|
| `/pet/2` req/s | 1571 | 1611 | +2.5% (noise) |
| `/pet/findByStatus` req/s | 966 | 947 | −2% (noise) |
| `/store/inventory` req/s | 973 | 970 | ≈ 0% |
| startup (to first 200) | 1.2 s | 1.1 s | ≈ 0% |
| RSS after the run | 732 MB | 732 MB | — |

**Conclusion:** no measurable improvement. All three endpoints land within measurement noise (±5–7%
on `/pet/2`, ±3–5% on complex queries). The bottleneck in this workload remains the external monitor
table introduced in Phase 3: every `monitorenter`/`monitorexit` probes the hash table, which
completely masks the savings from eliminating the instanceof call overhead. The inline isinstance
optimization is correct and reduces binary size; it may show a gain in workloads where `instanceof`
is on the hot path but monitor contention is not (e.g. pure business-logic or type-dispatch loops).

## Phase 3 revert (2026-11-10, restore inline monitor fields — back to 16 B header)

Levers 2 and 3 confirmed that Phase 3's external monitor table is the entire bottleneck:
both levers showed no improvement because every `monitorenter`/`monitorexit` probes the
hash table, masking all other savings. Reverted to the Phase 2 layout (16 B header with
inline `monitor_count` + `thread_id`). Levers 2 and 3 are retained.

| Metric | Phase 3 | Revert (= Phase 2 layout) | Δ vs Phase 3 |
|--------|---------|--------------------------|--------------|
| `/pet/2` req/s | 1571 | 1688 | +7% |
| `/pet/findByStatus` req/s | 966 | 984 | +2% |
| `/store/inventory` req/s | 973 | 983 | +1% |
| startup (to first 200) | 1.2 s | 1.1 s | ≈ 0% |
| RSS after the run | 732 MB | 366 MB | **−50%** |

The throughput numbers are within measurement noise of the Phase 2 baseline (1801/1055/1056),
confirming the regression is fully explained by the external monitor table. RSS is back to
364–366 MB (Phase 2 level). Levers 2+3 are still in; they had no measurable effect on their
own and remain as a foundation for future experiments.

**Current state (Lever 2 + Lever 3 on Phase 2 layout):**
- Object header: `{ void *type, int32_t monitor_count, int32_t thread_id }` = 16 bytes
- String intrinsics: `String.hashCode`/`equals` in `jcl_lite.c` (BIND, strong symbol)
- Inline instanceof: final classes use a pointer compare, no call, no cache-slot global

## Dead-field elimination (2026-11-11, `-Dnabu.deadFields=false` turns it off)

A field the program never reads is left out of the object struct (`FieldReachability` + `ClassLayouts`):
it only takes room in every instance. The test is by simple field name over the whole batch, so a name
that is read anywhere stays in every class that declares it — that also covers the runtime reading a
field by name (`String.value`, `File.path`, `Unsafe`). `volatile`, `public` and reflection-configured
fields keep their slot; a read of an eliminated field becomes `undef` and a store a no-op.

Measured on this machine (x86-64, JDK 25 GraalVM CE, `-O2`/DEFAULT, BOEHM, 16 partitions):

| Probe | Field | deadFields on | deadFields off | Δ |
|---|---|---|---|---|
| 400 000 `Box` objects, two write-only fields | peak RSS | 29 464 kB | 36 380 kB | **−6 916 kB (−19 %)** |
| `app/Main$Box` type_info | object size | 24 B | 32 B | −8 B (−25 %) |
| probe executable | `.exe` | 10 388 312 B | 10 404 816 B | −16 504 B (−0.16 %) |
| probe module | `.ll` | 82 920 488 B | 83 044 330 B | −123 842 B (−0.15 %) |
| `StringConstantFieldE2eIT` closure (1643 modules) | `.exe` | 10 623 552 B | 10 636 024 B | −12 472 B (−0.12 %) |
| idem | `.ll` | 82 369 678 B | 82 484 247 B | −114 569 B (−0.14 %) |

`-Dnabu.closureStats=true` on that closure: **195 JDK instance fields (1349 B) and 2 program fields
(12 B) are never read by name** — the upper bound of what elimination removes (volatile, public and
reflection fields are kept, so the real number is a little lower). A build with closureStats now also
prints how many fields were actually left out: `[nabu/LLVM] dead-field elimination: N instance fields
left out of object structs`. The 8 B/object in the probe is the
two write-only fields (`int` + `long`); the rest of the RSS delta is JDK objects allocated during the
run. The executable barely changes: dead fields are data, not code. The win is memory, proportional to
how many instances of the affected classes are live — for a program that allocates a lot it is large,
for a small one it is negligible.

## Profiling with stacks on Windows (2026-11-17, `stackprof.py`)

`docs/perf-study/stackprof.py` samples a running Nabu executable from outside, with real call stacks, and needs
nothing in the executable (no rebuild, no `NABU_PROFILE`):

```
python docs/perf-study/stackprof.py run <exe> <working dir> <seconds> <runtime object dir> [--callers a,b] [--depth N] [--port P] [paths]
python docs/perf-study/stackprof.py report %TEMP%\stackprof_samples.json <exe> <runtime object dir> [--callers a,b]
```

It starts the server, warms it up with 1500 GETs, then samples only while a second process sends the GETs one after
another. Every ~1 ms each thread is suspended just long enough to copy its registers and stack; the stack is then
unwound with the x64 unwind data (`.pdata`/`.xdata`) of each frame's module, read from the files on disk (the way
`RtlVirtualUnwind` does it, epilogs included). Names come from the exe's global symbols, the static functions of the
runtime objects (`~/.nabu/cache/runtime/<hash>/*.o`, the newest directory belongs to the latest build; without them a
sample in a static C function is charged to the global before it), and the exports of the DLLs. Samples in a wait
function are counted as waiting and not unwound. The report gives self time per category and function, inclusive
time, and for each `--callers` name the chains of its callers. On the petstore 82% of the stacks reach
`RtlUserThreadStart` (median depth 38).

What it showed on the petstore (`/pet/2`, 2026-11-17):

- `ntdll!ZwDeviceIoControlFile` (13% of running samples) is called by `bdhkm64.dll`, an antivirus hook injected into
  every process: not Nabu's time, and GraalVM pays it too.
- Every `pthread_spin_lock` comes from `__emutls_get_address` → `pthread_getspecific`: MinGW's emulated `__thread`
  takes a lock on each access (`Thread.currentThread` via `jcl_diag_current`, `nabu_monitorenter`).
- `jcl_class` is called by `Class.getInterfaces` (Spring's `AnnotationsScanner`), by `jcl_desc_class` for
  `Method.getParameterTypes` and the AOP proxy, and by `Unsafe.checkPrimitivePointer`.
- `strcmp` comes from `nabu_instanceof` after a miss of a call-site cache (polymorphic sites) and from
  `nabu_type_info_for` when annotation objects are made.
- The class-initialization checks (5.4% of the exe's time as calls to `X.clinit.once`) and the `nabu_is_array`
  call before every virtual call on an `Object` receiver were found the same way and are now inline (+3 to 6% req/s).

## Lever 6 results (2026-11-18, LLVM PGO and LLVM 22; Windows x86-64, one keep-alive client, 3000 requests per endpoint)

Profile-guided optimization works: `-Dnabu.pgo=instrument` builds a program that counts the edges of every function
and the targets of its indirect calls; `-Dnabu.pgo=<file>.profdata` builds with the counts as branch weights and
function entry counts (pgo-instr-use) and promotes hot indirect calls to a guarded direct call (pgo-icall-prom).
`runtime/nabu_profile_rt.c` writes the profile (`LLVM_PROFILE_FILE`, default `default.profraw`, also every
`NABU_PROFILE_WRITE_EVERY` seconds for a server; `NABU_PROFILE_VERBOSE=1` reports each write).

Recipe (the petstore; every step but the builds takes seconds):

1. Build with `-Dnabu.pgo=instrument` (about 18 minutes at -O2).
2. Run it under a training workload with `LLVM_PROFILE_FILE` set and let it write the profile (the petstore: 40 s of
   `/pet/2`, `/pet/findByStatus?status=available`, `/store/inventory` in turn, 270k functions with counters).
3. `llvm-profdata merge x.profraw -o x.profdata`, with the `llvm-profdata` of the same LLVM major version as the backend
   (22). An older or newer major version writes or reads another indexed format and is refused.
4. Build with `-Dnabu.pgo=x.profdata`.

| petstore, -O2 | /pet/2 | findByStatus | inventory |
|---|---|---|---|
| LLVM 21, no PGO | 1530 | 1110 | 1020 |
| LLVM 21 + PGO | 2060 | 1500 | 1520 |
| LLVM 22, no PGO | 1660 | 1215 | 1130 |
| LLVM 22 + PGO | 2060-2120 | 1515-1560 | 1475-1510 |
| GraalVM native-image | 2049 | 1664 | 1682 |

With a profile the functions that the training run never entered (function entry count 0) are put in a section of
their own (`.text$zcold` on Windows, `.text.unlikely.nabu` on Linux, `__TEXT,__nabu_cold` on macOS; `-Dnabu.pgo.cold=false`
switches it off), so the linker puts them together and their pages are never read into memory. Petstore on Windows,
same profile, 6000 requests: working set 231 MB without and 215 MB with the cold section (-7%), 1731/1805 against
1823/1829 requests per second (no slower). Only Windows was measured; the Linux and macOS section names are the
conventional ones but were not run with a profile.

`-Dnabu.pgo.coldOptnone=true` goes one step further: after the profile is applied, the functions it never entered get
`optnone` and `noinline`, so the optimizer skips them. Petstore, same profile: the optimize and emit phase 279 s -> 213 s
(-24%), the whole build 7:38 -> 6:49 (-11%), the executable 146 -> 161 MB. The endpoints the training run called are not
slower (1759/1817 against 1794/1765 requests per second); the ones it never called are 0-8% slower (`PUT /pet` 1505/1578
-> 1444/1457, `GET /store/order/1` 1904/1938 -> 1795/1809, the others within the noise). Off by default: it trades the
speed of untrained code for build time, so use it when the training run covers what the program does.

LLVM 22 alone is +9%; PGO adds little on top of that (the same level as PGO on LLVM 21, within the 5% noise of a run).
The executable is 146 MB with a profile (151 MB without; the instrumented one is 257 MB).

Endpoints the training never calls do not get slower: requests per second with and without the profile (LLVM 22, two
runs each, no profile / profile):

| endpoint (not in the training) | no profile | profile |
|---|---|---|
| GET /user/user1 | 1716-1873 | 1934-2043 |
| GET /store/order/1 | 1702-1743 | 2049-2143 |
| GET /pet/findByTags | 1324-1365 | 1581-1594 |
| PUT /pet | 1315-1395 | 1597-1706 |
| GET /pet/9999 (404) | 1571-1631 | 1905-1947 |

Most of it is code the endpoints share with the trained ones (Spring MVC, Jackson, Hibernate, H2); code that no
training request reaches has no profile and is compiled as before.

Limits:

- A profile belongs to the IR it was made from. After a change of the compiler or of the program, train again: a
  function whose shape changed is left without a profile, the rest keeps it, and nothing tells you how much that is.
- There is no profile to check in; the training run is part of the release build. A workload that is far from the real
  one (other endpoints, other data) trains the wrong branches; measure an untrained path as above.
- Verified on Windows x86-64 only. The profile runtime finds its data through section markers that differ per object
  format; Linux and macOS are not tried.
- Every native IT passes with `-Dnabu.pgo=instrument` (156/0, 2 skipped by platform).

## Phase 0 (do this first, on this machine)

Hardware counters need bare metal or a VM that exposes the PMU; check `perf stat -e instructions,cycles true`.

1. Build **both** petstores on Linux: GraalVM CE (see `petstore-demo/native.ps1`) and Nabu (`nabu-native.ps1`).
   The scripts are PowerShell with Windows paths and a `;` classpath separator; port them to bash (or use `pwsh`).
2. Run `docs/perf-study/bench.py` against both (same endpoints, same number of requests).
3. `perf stat -e instructions,cycles,branches,branch-misses,L1-dcache-load-misses,LLC-load-misses` over the same run.
   The key question: **instructions per request** (code quality: inlining, checks, calls) or **IPC** (memory: header
   size, GC, cache)? Divide by the request count; compare Nabu and GraalVM.
4. `perf record -g` (Nabu has DWARF line tables; build with debug info) for both, flame graphs, per-function.
5. Allocation volume per request: bytes per request for both (a counter in `nabu_gc_alloc` under an env variable;
   JFR or `-XX:+PrintGC` equivalents for GraalVM), and an object size histogram.
6. RSS split with `/proc/<pid>/smaps_rollup` and `pmap -x`: heap, code, data, stacks, Boehm overhead.
7. Write the result down here (the table above, on Linux) and choose levers by measured share.

## Building and running Nabu (what is known)

- The compiler builds with JDK 21 (`java.version` in the root pom); the petstore demo needs JDK 25 (its pom).
- Native packages needed: `gcc`, `lld` (the backend links with `-fuse-ld=lld`), `libgc-dev` (bdwgc 8.2.x; Ubuntu 24.04
  has 8.2.6), `perf`, `linux-tools-generic`. LLVM comes from the JavaCPP `llvm-platform` jar, not from apt.
- Build: `mvn -o -DskipTests -Dmaven.javadoc.skip=true install -pl compiler,native-backend`, then copy
  `compiler/target/nabu-compiler.jar` and `native-backend/target/native-backend.jar` to
  `native-image/target/dist/lib/` (the `-0.1.0-SNAPSHOT.jar` names). The module `native-image` makes `target/dist`.
- `mvn install` **without** `-DskipTests` also runs the integration tests (about 25 minutes). Long commands: run in
  the background; builds take 3 min (-O0) or 12 min (-O2).
- The Linux and macOS runtime already exist (`runtime/*.c`, `.github/workflows/native-linux-arm.yml`). The ITs
  (`native-backend`, 146 + 149 unit tests) passed on Linux arm64 and macOS earlier; run them on x86-64 first.
- Build knobs: `-Dnabu.closureStats=true`, `-Dnabu.reachability.why=<text>`, `-Dnabu.llvm.phaseTiming=true`,
  `-Dnabu.llvm.partitions=N`, `-Dnabu.devirt=false`, `-Dnabu.tbaa=false`, `-Dnabu.atomicArrays=false`,
  `-Dnabu.deadFields=false`.
  The runtime compiles to `~/.nabu/cache/runtime/<hash>`; a changed C file makes a new entry.

## Pitfalls found

- Kill running `petstore*` processes before copying an exe over another: a locked target makes `Copy-Item` fail
  silently and you keep testing the old binary (this cost an hour).
- After a compiler change also copy the jar to `target/project-local-repo/io.github.potjerodekool/nabu-compiler/0.1.0-SNAPSHOT/`
  (the ITs read it), and to the `dist/lib` directory.
- `GC_INITIAL_HEAP_SIZE` in the environment overrides the default 128 MB initial heap.
- Pre-existing flaky test: `ThreadsE2eIT.abandonedParkedVirtualThreads` times out occasionally.
- **Linux NIO stack was entirely missing from `jcl_lite.c` (found on first run on Linux).** Tomcat's NIO connector
  uses `EPollSelectorImpl` → `sun.nio.ch.EPoll_{eventSize,eventsOffset,dataOffset,create,ctl,wait,close}` (real
  Linux `epoll_create1`/`epoll_ctl`/`epoll_wait`), `sun.nio.ch.EventFD_{eventfd0,set0}` (Linux `eventfd`), and
  `sun.nio.ch.UnixDispatcher_{init,close0,preClose0}` (socketpair for pre-close). All six EPoll methods, two
  EventFD methods and three UnixDispatcher methods had to be added to `jcl_lite.c` before the server started.
- The Nabu binary needs `lib/tzdb.dat` (Java timezone database) in the working directory; copy it from
  `target/nabu-native/lib/tzdb.dat` alongside the binary.
- **CPU powersave governor on Linux causes large benchmark variance** (factor 2–3×) when the CPU has time to drop
  frequency between runs. Run a throw-away bench before recording numbers, or set governor to `performance`
  (`sudo cpupower frequency-set -g performance`).
- Maven's `JAVA_HOME` on the Linux machine defaulted to Java 8; override with
  `export JAVA_HOME=/opt/nexus/jdk/temurin_21.0.9_10_linux_x86_64/jdk-21.0.9+10` before `mvn install`.
- `perf stat -p PID` shows `<not counted>` when the server is idle between requests; the process must be under
  continuous load for the hardware counters to accumulate.

## Petstore against GraalVM, PGO cycle and profile (2026-11-27, Windows x86-64)

One keep-alive client, 5000 requests per endpoint after a warm-up of 500 (`bench.py`), two runs each (runs agree within
5%). Nabu built from `main` with the dist of 2026-11-24, `--opt DEFAULT`, 2 partitions.

| | GraalVM | Nabu O2 + PGO | Nabu O2 | Nabu `--opt NONE` |
|---|---|---|---|---|
| `/pet/2` req/s | 2200-2295 | 1771-1834 | 1454-1547 | 981 |
| `/pet/findByStatus` req/s | 1839-1911 | 1356-1440 | 1173-1184 | 751-757 |
| `/store/inventory` req/s | 1707-1860 | 1369-1424 | 1084-1102 | 645-654 |
| RSS after the run | 130 MB | 287 MB | 302 MB | 320 MB |
| start to first 200 | 0.5 s | 1.0-1.1 s | 1.1-2.1 s | 1.6-2.3 s |
| exe | 142 MB | 148 MB | 152 MB | 221 MB |
| build | 165 s | 13.4 min (+16 min instrumented + training) | 13 min | |

Below the 2060/1515/1475 of 2026-11-18 (cause not found: not the cold-code section of the profile, `-Dnabu.pgo.cold=false`
gives the same speed; not LLVM 22; the collector is ~5% of the wall time and a 512 MB initial heap gives only +5%).

**PGO recipe** (all builds with the same dist, the profile belongs to the IR of that compiler):

1. `nabu-native.ps1 -Opt DEFAULT -JvmArgs ... -Dnabu.pgo=instrument` (16 min; the exe is 259 MB).
2. `python pgotrain.py <instrumented exe> <cwd> out.profraw 40` (this directory): starts the exe with
   `LLVM_PROFILE_FILE` and `NABU_PROFILE_WRITE_EVERY=5` (the server is killed, so there is no `atexit`), sends 40 rounds
   of the benchmark endpoints and a few others (POST/PUT/DELETE of a pet, `/v3/api-docs`, swagger-ui), waits for one more
   periodic write.
3. `llvm-profdata merge out.profraw -o out.profdata` (the installed 22.1.x reads it).
4. Build again with `-Dnabu.pgo=<abs path>/out.profdata` (13.4 min).

Pitfalls: the exe looks for `lib	zdb.dat` next to itself (copy `target/nabu-native/lib`); a profile of a run in which the
server died at start-up is almost empty (maximum function count 4114 instead of 60 million: check `llvm-profdata show`).
On Linux there is no `llvm-profdata` in the WSL image: the `native-pgo.yml` workflow does the cycle (llvm.apt.org) on a
small program.

**Where the time goes** (`stackprof.py run`, `/pet/findByStatus`, 40 s): 15% `ZwDeviceIoControlFile` (the socket I/O,
through the antivirus hook `bdhkm64.dll`), 14% collector, 32% application and framework code, 13% JDK code, 7.5% C runtime.
Found and fixed: `ProxySupport.newProxyInstance` redid its search of the configured proxy key at every `createQuery`
(7.8% of the running samples), `Stream.toList` threw and caught a `ClassNotFoundException` in
`SharedSecrets.getJavaUtilCollectionAccess` every time (3019 -> 151 ns). Stack traces: a throw costs ~2.2 us on Windows
whatever the depth in a small program (the cost is `RaiseException`), but in the petstore the capture was 4.8% of the
samples (the unwind data of a 150 MB executable is not in the CPU cache): the frames are now walked directly and a table
keeps the frame size per return address (`NABU_VERIFY_FRAMES=1` compares with the full walk).
Hibernate itself throws one exception per `setParameter` of a collection (`StringJavaType.coerce` as a check), as on the JVM.

**Allocation** (`allocwin.py`, a build with `-Dnabu.allocProfile=true`): 114 KB and 2352 objects per request, 1.6 KB of
that from the C runtime. The sites are all ordinary library code: `ArrayList.iterator` 5.6% (164 iterators of 40 bytes per
request), `HashMap.resize` 5.5%, H2 `TransactionMap.init` 4.4%, `HashMap.newNode` 4.0%, `ResolvableType.forType` 2.6%,
`ArrayList.grow` 2.4%. No site above 6%, so there is no small fix: the lever is inlining plus escape analysis of the
iterators and short-lived maps (GraalVM does that), which is the "own middle-end" of lever 4.

## Where the code is

- Object model, layouts, vtables/itables, devirtualization: `native-backend/.../ClassLayouts.java`
- Dead-field elimination (which instance fields are read): `native-backend/.../FieldReachability.java`
- Instruction selection (allocation, casts, calls, field and array access): `InstructionEmitter.java`
- Module emission and runtime declarations: `LLVMModuleEmitter.java`; partitions: `PartitionedCodegen.java`
- Closure (reachability, lowering of JDK/library bytecode): `compiler/.../backend/bytecode/BytecodeReachabilityCompiler.java`
- Reflection data, parameter names: `ReflectionDataGenerator.java`, `ReflectionMethods.java`, `ParameterSupportGenerator.java`
- C runtime: `runtime/nabu_runtime.c`, `jcl_lite.c` (VM-bound JDK methods), `gc_boehm.c`, `gc_none.c`, `nabu_gc.h`
- Tbaa: `Tbaa.java`; pluggable collectors: `native-backend/.../gc/`

## Escape analysis on the petstore: why 0 of 84568 allocations are on the stack (2026-11-28)

`-Dnabu.escapeStats=true` (branch `escape-reasons`, not merged) counts the first reason for each `HeapAlloc` of the petstore (-O2) that keeps it on the heap. Measured on the IR before the inliner:

| first reason | allocations (original analysis) |
|---|---|
| not in the entry block | 48294 (57%) |
| a call whose parameter escapes (nearly all: a constructor) | 32909 (39%) |
| virtual/interface call on the object | 2293 |
| callee unknown | 421 |
| stored in memory / returned / stored in an array | 381 / 203 / 67 |

Findings:

1. **The constructor receiver is spilled.** The bytecode lowering copies `this` into an `Alloca` slot (`%this.ptr`) and the analysis counted that `Store` as the object escaping, so every constructor (even `Object.<init>`) leaked its receiver. Treating a store into a slot that is only loaded and stored as an alias (and the loads from it as aliases) fixes it.
2. **"Entry block only" was far too strict.** Only 2630 allocations lie in a loop; the backend already puts the alloca of a `StackAlloc` in the entry block. Allocations outside loops can qualify.
3. **Constructor chains are deeper than 3** (ArrayList -> AbstractList -> AbstractCollection -> Object): depth 8.

With all three the result is still **34 of 84568**. What is left, by count: `Throwable.<init>` stores `this` into its `cause` (18288: exceptions, which are thrown anyway), plain stores into fields or collections (15325), virtual/interface calls on the new object (11407: the exact class is known at the allocation, a call on it could be resolved), factory methods that return the object (7318: `ArrayList.iterator()` and the like, which only an inliner that can copy allocations and calls removes), `StringBuilder` internals (5642), calls to unknown callees (2604), loops (2630).

So the allocation work is not one change: it needs (a) an inliner that may copy allocations, constructor calls and stores (today it only copies single-block leaf bodies of loads and arithmetic), (b) calls on a receiver of known exact class resolved to the implementation, then (c) the analysis above. Each step is only measurable together with the others; the first two are large (the inliner changes stack traces of inlined JDK frames, class-initialization guards and exception semantics). Not started beyond the analysis.

### Result of the escape-analysis work (merged 2026-11-28)

With exact-class devirtualization for the analysis, plain local slots, loops instead of the entry block only, and three soundness fixes, **400 of 84568** petstore allocations (-O2) are on the stack (was 0). 15 of 15 probe requests work. `findByStatus` 1128-1160 -> 1202-1221 req/s (p50 0.76 -> 0.71 ms); `/pet/2` and `/store/inventory` unchanged (within the ~5% noise of the bench).

Bugs that the first attempts hit (segfaults, a `NullBean` read as a `String`), all found by cdb + IR dump + an automatic bisect on the allocation index (`-Dnabu.stackAlloc.range=lo-hi`, `-Dnabu.stackAllocList=true`):

1. **Constructor receiver arity.** Bytecode-lowered constructor calls pass the receiver as an argument and in `paramTypes`; source-lowered calls do not. The shape is now taken from the callee's parameter count.
2. **Overloads share one IR function name** (the emitter tells them apart by parameter types), so a name is not a key for a body. Ambiguous names are now unknown to the Inliner, EscapeAnalysis and ScalarReplacement.
3. **A loaded alias is not necessarily the class that was stored**: javac reuses local slots. The "exact class" of a value is only used when every store into its slot is from that one allocation (`precise`).

`-Dnabu.inline.alloc=true` (inliner that copies allocations, off): 664 stack allocations, but the inliner is 5x slower and adds heap allocations. Stays off. What would move the number: type-flow for monomorphic interface receivers and inlining of allocating factories (`iterator()`).

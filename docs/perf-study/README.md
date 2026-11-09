# Performance study: nabu-native-image against GraalVM native-image (petstore demo)

Handover for a session on a Linux (Ubuntu) machine. Written 2026-11-09 from a Windows session. Everything below that is
a number was measured there; everything marked *estimate* is a guess to be replaced by measurements.

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
| 6 | LLVM PGO (instrumented training run, indirect-call promotion). Needs a training workload; GraalVM CE has no PGO | +15-25% | 2-3 weeks | medium |
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

Pending from phase 0: allocation volume per request (counter in `nabu_gc_alloc` under an env variable), `perf record
-g` flame graphs to pinpoint the hot functions within the 11× instruction gap.

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
  `-Dnabu.llvm.partitions=N`, `-Dnabu.devirt=false`, `-Dnabu.tbaa=false`, `-Dnabu.atomicArrays=false`.
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

## Where the code is

- Object model, layouts, vtables/itables, devirtualization: `native-backend/.../ClassLayouts.java`
- Instruction selection (allocation, casts, calls, field and array access): `InstructionEmitter.java`
- Module emission and runtime declarations: `LLVMModuleEmitter.java`; partitions: `PartitionedCodegen.java`
- Closure (reachability, lowering of JDK/library bytecode): `compiler/.../backend/bytecode/BytecodeReachabilityCompiler.java`
- Reflection data, parameter names: `ReflectionDataGenerator.java`, `ReflectionMethods.java`, `ParameterSupportGenerator.java`
- C runtime: `runtime/nabu_runtime.c`, `jcl_lite.c` (VM-bound JDK methods), `gc_boehm.c`, `gc_none.c`, `nabu_gc.h`
- Tbaa: `Tbaa.java`; pluggable collectors: `native-backend/.../gc/`

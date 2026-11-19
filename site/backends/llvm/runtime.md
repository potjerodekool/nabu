---
layout: page
title: Runtime tuning and diagnostics
---

A native executable made by the [LLVM backend]({{ site.baseurl }}/backends/llvm/) reads a number of environment variables at run time, and the compiler reads a number of system properties at build time. This page lists the ones that tune the memory management and help to investigate a program. For crashes and hangs see [Troubleshooting]({{ site.baseurl }}/backends/llvm/troubleshooting/); for the threads, see [Known limitations]({{ site.baseurl }}/limitations/#run-time).

## Memory management (Boehm GC)

The default collector is the Boehm collector, with allocation from thread-local free lists. Two of its settings are different from the library's defaults, because that suited a server program better (the petstore example: Spring, JPA, H2 and Tomcat):

| Variable | Default | Meaning |
|---|---|---|
| `GC_MARKERS` | `1` | the number of marker threads. Marking in parallel has to hand work from thread to thread at every collection, which cost more than it saved for heaps of a few hundred megabytes: the petstore served 10 to 20% more requests per second with one marker. A value in the environment wins. |
| `GC_INITIAL_HEAP_SIZE` | 256 MB | the heap at the start. A program that builds a large object graph at start-up otherwise collects over and over while all of it is still alive. Against 128 MB the petstore served 13% (one request) to 44% (another) more requests per second, and used 86 MB more memory. The memory of a program that does not allocate that much is not touched. A value in the environment wins. |

Other `GC_*` variables are read by the collector itself (see the [bdwgc documentation](https://github.com/ivmai/bdwgc/blob/master/doc/README.environment)).

### Weak, soft and phantom references

`WeakReference` and `SoftReference` are weak: the collector clears the referent when nothing else refers to the object, and a reference with a `ReferenceQueue` is put on the queue (when the queue is asked, `poll` or `remove`, after a collection; there is no reference handler thread). Caches that are made of them (Spring's `ConcurrentReferenceHashMap`, `WeakHashMap`, the `ThreadLocal` maps) therefore shrink; without this a server grew by a few megabytes every thousand requests until it ran out of memory (the petstore: 25 MB of live data after 3000 requests, 54 MB after 25000). A `PhantomReference` stays strong.

A soft reference is kept longer than a weak one, as in a JVM: the referents of soft references are kept as roots, except in every 8th collection and when the heap has grown to more than one and a half times its initial size. A cache of soft references survives most collections and cannot grow without bound.

| Variable | Default | Meaning |
|---|---|---|
| `NABU_SOFT_REF_EVERY` | `8` | soft references are cleared in every this many collections (when nothing else refers to the referent) |
| `NABU_WEAK_REFS` | all | `0`: every reference is as strong as any other pointer (how it was before); `weak`: only `WeakReference` is weak |

On the petstore the long run (20000 requests per endpoint) served 1098 requests per second for `/store/inventory` where the build with strong references fell from 1170 to 926 while its live data grew; `/pet/2` is about 3% slower (every weak or soft reference registers itself with the collector).

### Looking at the collector

| Variable | Meaning |
|---|---|
| `NABU_GC_STATS=1` | one line on standard error per collection: its number, how long it took, the size of the heap, and how many bytes are live afterwards |
| `NABU_GC_ALLOC_STATS=1` | the total number of bytes the program allocated, printed at exit |
| `NABU_ALLOC_GLOBAL=1` | allocate without the thread-local free lists. Slower; it is there to tell a bug of the free lists from a bug of the program |

**Which code allocates?** Build with `-Dnabu.allocProfile=true`: every allocation then counts its bytes and its objects against the place in the code where it happens. Run the program with `NABU_ALLOC_PROF=<file>`: the counts are written to that file at every fourth collection. Without the property nothing is counted.

## Threads and the machine

| Variable | Meaning |
|---|---|
| `NABU_CPUS=<n>` | the number returned by `Runtime.availableProcessors()` (default: the number of logical processors) |
| `NABU_GIL=1` | run the threads one at a time, see [Known limitations]({{ site.baseurl }}/limitations/#run-time) |
| `NABU_CONTINUATIONS=0` | give every virtual thread a native thread, see [Known limitations]({{ site.baseurl }}/limitations/#run-time) |
| `NABU_PROPERTIES="k=v;k2=v2"` | system properties of the program. A native executable has no JVM options, so `-Dkey=value` becomes `NABU_PROPERTIES=key=value`. |

## Diagnostics

| Variable | Meaning |
|---|---|
| `NABU_TRACE_NPE=1` | print where each null check that fails is. Build with `-Dnabu.npeSites=true` for the call site to be named. |
| `NABU_TRACE_FRAMES=1` | print each address that is looked up for a stack trace, with the function it belongs to |
| `NABU_TRACE_EPOLL=1` | print the registrations and waits of the selector (the `wepoll` emulation of Windows) |
| `NABU_TRACE_UNSAFE=1` | print the `Unsafe.objectFieldOffset` lookups that find no field |
| `NABU_DIAG=1` | check the bookkeeping of the threads: a `Thread` object that does not belong to the thread that uses it, or a `ThreadLocal` map that two threads hold, is reported on standard error as `[nabu/diag] …`. It is for hunting threads that share something they should not. |

Build time:

| Property | Meaning |
|---|---|
| `-Dnabu.checkLayout=true` | list the classes whose objects are larger than the layout the backend computed: a check on the object model |
| `-Dnabu.closureStats=true`, `-Dnabu.reachability.stats=true` | how many classes and methods the closure has, and which packages they come from |
| `-Dnabu.reachability.why=<text>` | which call brought a class into the closure (see [Internals]({{ site.baseurl }}/backends/llvm/internals/#compiling-the-jdk-from-bytecode)) |
| `-Dnabu.llvm.phaseTiming=true`, `-Dnabu.llvm.partitionTiming=true` | the time of the phases of code generation and of every partition |
| `-Dnabu.opt.<pass>=false` | switch one optimizer pass off to find the pass that causes a bug: `inline`, `branches`, `unreachable`, `fold`, `copyprop`, `gvn`, `dce` |
| `-Dnabu.stackAlloc=false` | do not put objects that do not escape a method on the stack |
| `-Dnabu.deadFields=false` | keep fields that nothing reads (the backend leaves them out otherwise) |
| `-Dnabu.optimizeClosure=false` | skip the optimizer that runs over the whole closure of classes |

## Network

Sockets are **dual stack**: a server socket accepts both IPv4 and IPv6 clients (IPv4 addresses arrive as IPv4-mapped IPv6 addresses), and host names resolve to both kinds of address. A client that tries `::1` for `localhost` first finds the server.

## Performance, as measured

The numbers below are from the petstore example (Spring, Hibernate, H2, Tomcat; 14 REST endpoints) built with `nabu-native-image` on one Windows machine with MinGW, requests one at a time over one keep-alive connection to `127.0.0.1`. They are for orientation: other programs, other hardware and other settings give other numbers.

| | Nabu (LLVM, `-O2`) | GraalVM native image |
|---|---|---|
| requests per second | about 1.7 to 2.3 times fewer than GraalVM | the reference |
| resident memory | about 3 times | the reference |
| start | about 2 s | about 0.5 s |
| time to build | about 15 minutes (`--opt NONE`: 3 to 4 minutes) | about 2.5 minutes |
| size of the executable | hundreds of MB (575 MB with `--opt NONE`) | 135 MB |

What was found to matter most for a server: the Boehm settings above, a hash index for the type lookups at start (the start went from 95 s to 2 s), and the dual-stack sockets (a client that tries IPv6 first lost up to two seconds per new connection when the server listened on IPv4 only). The build time and the size of the executable depend most on the optimization level; see the [compiler options]({{ site.baseurl }}/backends/llvm/#compiler-options).

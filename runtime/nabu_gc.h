/*
 * The interface between the Nabu runtime and the garbage collector.
 *
 * A collector is one C file that implements these functions (gc_boehm.c, gc_none.c); the link picks one of them (the
 * collector's Java side, nabu.compiler.backend.native_llvm.gc.GarbageCollector, names the file and the macro). The rest of
 * the runtime (nabu_runtime.c, jcl_lite.c) allocates and registers roots only through this header, so a new collector
 * does not touch them.
 *
 * What the compiled code needs from the collector is separate: it calls the two allocation symbols that the Java side
 * of the collector names (for Boehm GC_malloc and GC_malloc_atomic, for no collection nabu_none_alloc and
 * nabu_none_alloc_atomic), and the collector is started from a constructor of its own C file, before main.
 */
#ifndef NABU_GC_H
#define NABU_GC_H

#include <stddef.h>
#include <stdint.h>

/* 1 when the collector can scan the stacks of continuations (virtual threads): the functions of the last section exist. */
#if defined(NABU_GC_BOEHM)
#define NABU_GC_CONTINUATIONS 1
#else
#define NABU_GC_CONTINUATIONS 0
#endif

/* ---- start ---- */

/* Starts the collector; calling it again does nothing. The collector's C file calls it from a constructor, and the
 * compiled program from the first of its own constructors (a linker does not always keep to the priorities). */
void nabu_gc_init(void);

/* ---- memory ---- */

/* Zeroed memory that may hold pointers: scanned by the collector. */
void *nabu_gc_alloc(size_t n);
/* Memory without pointers (text, bytes, arrays of numbers): not scanned. The contents are not cleared. */
void *nabu_gc_alloc_atomic(size_t n);
/* Zeroed memory that is scanned for pointers and not collected: it lives until nabu_gc_free. For the structures that
 * the runtime links into its own lists. */
void *nabu_gc_alloc_pinned(size_t n);
/* A resized block of nabu_gc_alloc memory (the new part is not cleared). */
void *nabu_gc_realloc(void *p, size_t n);
/* Gives back pinned memory (or a traced object, see nabu_gc_alloc_traced). */
void nabu_gc_free(void *p);
/* Tells that nothing refers to this nabu_gc_alloc block any more. A collector that finds that out by itself ignores it;
 * one that does not collect (gc_none.c) frees the memory. */
void nabu_gc_discard(void *p);

/* ---- collections ---- */

/* System.gc(): collects, and runs the finalizers that this makes due. */
void nabu_gc_collect(void);
/* Calls fn(obj, NULL) some time after obj became unreachable (also when objects that refer to each other become unreachable
 * together); fn == NULL removes the finalizer. A collector that never collects never calls it. */
void nabu_gc_set_finalizer(void *obj, void (*fn)(void *obj, void *data));
/* No collection starts between pause and resume (they nest). */
void nabu_gc_pause(void);
void nabu_gc_resume(void);

/* ---- threads ---- */

/* Called once, before the first thread besides the main one is made. */
void nabu_gc_threads_enabled(void);
/* A detached native thread that runs start(arg) with a stack of stack_size bytes, known to the collector. 0 when it was made. */
int nabu_gc_thread_create(void *(*start)(void *), void *arg, size_t stack_size);

/* ---- stacks of continuations (fibers, ucontext); only when NABU_GC_CONTINUATIONS is 1 ---- */

/* Called while the collector looks for roots: scanner calls scan(lo, hi) for every region of memory that holds roots (the
 * stacks of suspended continuations). */
void nabu_gc_add_root_scanner(void (*scanner)(void (*scan)(void *lo, void *hi)));
/* The collector's handle of the calling thread, and the end of the stack that it scans for that thread. */
void *nabu_gc_thread_handle(void);
void *nabu_gc_stack_base(void);
/* The thread continues on another stack that ends at hi (a continuation, or its carrier's own stack again). */
void nabu_gc_set_stack_base(void *thread_handle, void *hi);
/* Zeroed memory that the collector scans by calling trace(obj, mark) and not conservatively: trace calls mark(p) for each
 * word that may refer to an object. Lives until nabu_gc_free or unreachable (see nabu_gc_set_finalizer). */
void *nabu_gc_alloc_traced(size_t n, void (*trace)(void *obj, void (*mark)(void *candidate)));

#endif

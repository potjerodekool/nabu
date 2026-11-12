/*
 * The Boehm-Demers-Weiser collector behind the interface of nabu_gc.h. Compiled with -DNABU_GC_BOEHM and linked with
 * -lgc. The compiled code calls GC_malloc and GC_malloc_atomic itself.
 */
#ifndef NABU_GC_BOEHM
#error "gc_boehm.c needs -DNABU_GC_BOEHM"
#endif

#ifdef _WIN32
#include <windows.h>
#else
#include <pthread.h>
#include <sys/syscall.h>
#include <unistd.h>
#endif
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <gc.h>
#include "nabu_gc.h"

/* The collector starts before the constructors of the compiled program (default priority) and before main. */
extern int GC_expand_hp(size_t bytes);

void nabu_gc_init(void) {
    static volatile int started = 0;
    if (!started) {
        started = 1;
        GC_init();
        /* A program like this starts by building a large object graph. With the small heap the collector begins with it
         * collects over and over while all of it is still alive (the petstore: 2.7 -> 2.1 s to start, +20 MB resident,
         * and fewer collections afterwards). GC_INITIAL_HEAP_SIZE in the environment, which the collector reads
         * itself, takes precedence. */
        if (getenv("GC_INITIAL_HEAP_SIZE") == NULL) {
            GC_expand_hp((size_t) 128 << 20);
        }
    }
}

__attribute__((constructor(101))) static void nabu_boehm_init(void) {
    nabu_gc_init();
}

static volatile size_t nabu_gc_alloc_bytes = 0;
static void nabu_gc_alloc_stats_report(void) {
    if (getenv("NABU_GC_ALLOC_STATS") != NULL) {
        fprintf(stderr, "[nabu/gc] total allocated bytes: %zu\n", nabu_gc_alloc_bytes);
    }
}
__attribute__((constructor(102))) static void nabu_gc_alloc_stats_init(void) {
    atexit(nabu_gc_alloc_stats_report);
}

void *nabu_gc_alloc(size_t n) { nabu_gc_alloc_bytes += n; return GC_malloc(n); }
void *nabu_gc_alloc_atomic(size_t n) { nabu_gc_alloc_bytes += n; return GC_malloc_atomic(n); }
void *nabu_gc_alloc_pinned(size_t n) { return GC_malloc_uncollectable(n); }
void *nabu_gc_realloc(void *p, size_t n) { return GC_realloc(p, n); }
void nabu_gc_free(void *p) { GC_free(p); }
void nabu_gc_discard(void *p) { (void) p; /* the wrapper of an exception and the like are reclaimed by the collector */ }

void nabu_gc_collect(void) {
    GC_gcollect();
    GC_invoke_finalizers();
}

void nabu_gc_set_finalizer(void *obj, void (*fn)(void *obj, void *data)) {
    /* the unordered variant: it also finalizes objects that refer to each other */
    GC_register_finalizer_no_order(obj, fn, NULL, NULL, NULL);
}

void nabu_gc_pause(void) { GC_disable(); }
void nabu_gc_resume(void) { GC_enable(); }

/* ---- threads ---- */

#ifdef _WIN32
extern void GC_allow_register_threads(void);
extern int GC_register_my_thread(const struct GC_stack_base *base);
extern int GC_unregister_my_thread(void);

void nabu_gc_threads_enabled(void) { GC_allow_register_threads(); }

typedef struct { void *(*start)(void *); void *arg; } nabu_boehm_thread;

/* A thread made by pthread_create gets its whole stack committed at once. A thread made by CreateThread with
 * STACK_SIZE_PARAM_IS_A_RESERVATION only reserves the address space and commits the pages it touches, so a big stack costs
 * nothing until it is used. The collector is told about the thread by hand. */
static DWORD WINAPI nabu_boehm_thread_main(LPVOID parameter) {
    nabu_boehm_thread t = *(nabu_boehm_thread *) parameter;
    free(parameter);
    struct GC_stack_base base;
    GC_get_stack_base(&base);
    GC_register_my_thread(&base);
    t.start(t.arg);
    GC_unregister_my_thread();
    return 0;
}

int nabu_gc_thread_create(void *(*start)(void *), void *arg, size_t stack_size) {
    nabu_boehm_thread *t = (nabu_boehm_thread *) malloc(sizeof *t);
    if (t == NULL) return -1;
    t->start = start;
    t->arg = arg;
    HANDLE created = CreateThread(NULL, stack_size, nabu_boehm_thread_main, t, STACK_SIZE_PARAM_IS_A_RESERVATION, NULL);
    if (created == NULL) { free(t); return -1; }
    CloseHandle(created);
    return 0;
}
#else
extern int GC_pthread_create(pthread_t *thread, const pthread_attr_t *attr, void *(*start)(void *), void *arg);

void nabu_gc_threads_enabled(void) { }

int nabu_gc_thread_create(void *(*start)(void *), void *arg, size_t stack_size) {
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
    pthread_attr_setstacksize(&attr, stack_size);
    pthread_t handle;
    const int rc = GC_pthread_create(&handle, &attr, start, arg);
    pthread_attr_destroy(&attr);
    return rc;
}
#endif

/* ---- stacks of continuations ---- */

#ifndef _WIN32
/* The collector scans the stack of the process's first thread up to GC_stackbottom, that of the others up to the stack end
 * it has recorded for them. */
static int nabu_boehm_on_main_thread(void) {
    static __thread int known = 0, main_thread = 0;
    if (!known) {
#ifdef __APPLE__
        main_thread = pthread_main_np() != 0;
#else
        main_thread = (pid_t) syscall(SYS_gettid) == getpid();
#endif
        known = 1;
    }
    return main_thread;
}
#endif

void *nabu_gc_thread_handle(void) {
    struct { void *mem_base; } sb;
    sb.mem_base = NULL;
    return GC_get_my_stackbottom((struct GC_stack_base *) &sb);
}

void *nabu_gc_stack_base(void) {
#ifndef _WIN32
    if (nabu_boehm_on_main_thread()) return (void *) GC_stackbottom;
#endif
    struct { void *mem_base; } sb;
    sb.mem_base = NULL;
    GC_get_my_stackbottom((struct GC_stack_base *) &sb);
    return sb.mem_base;
}

void nabu_gc_set_stack_base(void *thread_handle, void *hi) {
    struct { void *mem_base; } sb;
    sb.mem_base = hi;
    GC_set_stackbottom(thread_handle, (const struct GC_stack_base *) &sb);
#ifndef _WIN32
    if (nabu_boehm_on_main_thread()) GC_stackbottom = (char *) hi;
#endif
}

extern void GC_push_all_eager(void *bottom, void *top);
extern void GC_set_push_other_roots(void (*proc)(void));
extern void (*GC_get_push_other_roots(void))(void);

/* The regions that scanners report are roots of every collection. */
#define NABU_BOEHM_MAX_SCANNERS 8
static void (*nabu_boehm_scanners[NABU_BOEHM_MAX_SCANNERS])(void (*scan)(void *lo, void *hi));
static int nabu_boehm_scanner_count = 0;
static void (*nabu_boehm_old_roots)(void) = NULL;
#ifdef _WIN32
static CRITICAL_SECTION nabu_boehm_lock;
static INIT_ONCE nabu_boehm_lock_once = INIT_ONCE_STATIC_INIT;
static BOOL CALLBACK nabu_boehm_lock_init(PINIT_ONCE o, PVOID p, PVOID *c) { (void) o; (void) p; (void) c; InitializeCriticalSection(&nabu_boehm_lock); return TRUE; }
#define NABU_BOEHM_LOCK() do { InitOnceExecuteOnce(&nabu_boehm_lock_once, nabu_boehm_lock_init, NULL, NULL); EnterCriticalSection(&nabu_boehm_lock); } while (0)
#define NABU_BOEHM_UNLOCK() LeaveCriticalSection(&nabu_boehm_lock)
#else
static pthread_mutex_t nabu_boehm_lock = PTHREAD_MUTEX_INITIALIZER;
#define NABU_BOEHM_LOCK() pthread_mutex_lock(&nabu_boehm_lock)
#define NABU_BOEHM_UNLOCK() pthread_mutex_unlock(&nabu_boehm_lock)
#endif

static void nabu_boehm_scan_region(void *lo, void *hi) { GC_push_all_eager(lo, hi); }

static void nabu_boehm_push_other_roots(void) {
    for (int i = 0; i < nabu_boehm_scanner_count; i++) nabu_boehm_scanners[i](nabu_boehm_scan_region);
    if (nabu_boehm_old_roots != NULL) nabu_boehm_old_roots();
}

void nabu_gc_add_root_scanner(void (*scanner)(void (*scan)(void *lo, void *hi))) {
    NABU_BOEHM_LOCK();
    if (nabu_boehm_scanner_count == 0) {
        nabu_boehm_old_roots = GC_get_push_other_roots();
        GC_set_push_other_roots(nabu_boehm_push_other_roots);
    }
    if (nabu_boehm_scanner_count < NABU_BOEHM_MAX_SCANNERS) nabu_boehm_scanners[nabu_boehm_scanner_count++] = scanner;
    NABU_BOEHM_UNLOCK();
}

/* ---- objects with their own tracer ---- */

struct GC_ms_entry;
extern struct GC_ms_entry *GC_mark_and_push(void *obj, struct GC_ms_entry *top, struct GC_ms_entry *limit, void **src);
extern unsigned GC_new_proc(struct GC_ms_entry *(*proc)(uintptr_t *, struct GC_ms_entry *, struct GC_ms_entry *, uintptr_t));
extern unsigned GC_new_kind(void **free_list, uintptr_t descriptor, int add_size, int clear);
extern void **GC_new_free_list(void);
extern void *GC_malloc_kind(size_t n, int kind);

typedef void (*nabu_boehm_trace_fn)(void *obj, void (*mark)(void *candidate));

#define NABU_BOEHM_MAX_TRACERS 8
static nabu_boehm_trace_fn nabu_boehm_tracers[NABU_BOEHM_MAX_TRACERS];
static int nabu_boehm_tracer_kinds[NABU_BOEHM_MAX_TRACERS];
static int nabu_boehm_tracer_count = 0;
static int nabu_boehm_trace_proc_index = -1;

/* The state of the collection that is marking on this thread, for nabu_boehm_mark. */
static __thread struct GC_ms_entry *nabu_boehm_msp;
static __thread struct GC_ms_entry *nabu_boehm_limit;

static void nabu_boehm_mark(void *candidate) {
    nabu_boehm_msp = GC_mark_and_push(candidate, nabu_boehm_msp, nabu_boehm_limit, NULL);
}

static struct GC_ms_entry *nabu_boehm_trace_proc(uintptr_t *addr, struct GC_ms_entry *msp, struct GC_ms_entry *limit,
                                                 uintptr_t env) {
    nabu_boehm_msp = msp;
    nabu_boehm_limit = limit;
    nabu_boehm_tracers[env]((void *) addr, nabu_boehm_mark);
    return nabu_boehm_msp;
}

void *nabu_gc_alloc_traced(size_t n, void (*trace)(void *obj, void (*mark)(void *candidate))) {
    int kind = -1;
    NABU_BOEHM_LOCK();
    for (int i = 0; i < nabu_boehm_tracer_count; i++) {
        if (nabu_boehm_tracers[i] == trace) { kind = nabu_boehm_tracer_kinds[i]; break; }
    }
    if (kind < 0 && nabu_boehm_tracer_count < NABU_BOEHM_MAX_TRACERS) {
        if (nabu_boehm_trace_proc_index < 0) nabu_boehm_trace_proc_index = (int) GC_new_proc(nabu_boehm_trace_proc);
        const int slot = nabu_boehm_tracer_count++;
        nabu_boehm_tracers[slot] = trace;
        /* the descriptor of a mark procedure (GC_MAKE_PROC): ((environment << 6) | procedure) << 2 | GC_DS_PROC (2); the
         * environment is the index of the tracer */
        kind = (int) GC_new_kind(GC_new_free_list(),
                ((((uintptr_t) slot << 6) | (uintptr_t) nabu_boehm_trace_proc_index) << 2) | 2, 0, 1);
        nabu_boehm_tracer_kinds[slot] = kind;
    }
    NABU_BOEHM_UNLOCK();
    if (kind < 0) return NULL;
    nabu_gc_alloc_bytes += n;
    return GC_malloc_kind(n, kind);
}

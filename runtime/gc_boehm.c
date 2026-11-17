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
#include <fcntl.h>
#include <pthread.h>
#include <sys/syscall.h>
#include <unistd.h>
#endif
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include <unwind.h>

#include <gc.h>
#include "nabu_gc.h"

/* The collector starts before the constructors of the compiled program (default priority) and before main. */
extern int GC_expand_hp(size_t bytes);

void nabu_gc_init(void) {
    static volatile int started = 0;
    if (!started) {
        started = 1;
        /* One marker thread. Marking in parallel pays the hand-off between the threads on every collection, and for the
         * heaps of a server like the petstore (a few hundred MB, 30 threads, one of them running under the lock) that is
         * more than it saves: the petstore serves 10-20% more requests per second with GC_MARKERS=1. The collector reads
         * GC_MARKERS itself in GC_init, so an explicit value in the environment wins. */
        if (getenv("GC_MARKERS") == NULL) {
#ifdef _WIN32
            _putenv("GC_MARKERS=1");
#else
            setenv("GC_MARKERS", "1", 0);
#endif
        }
        GC_init();
        /* A program like this starts by building a large object graph. With the small heap the collector begins with it
         * collects over and over while all of it is still alive (the petstore: 2.7 -> 2.1 s to start, +20 MB resident,
         * and fewer collections afterwards). 256 MB: the petstore serves 13% (/pet/2) to 44% (/store/inventory) more
         * requests per second than with 128 MB, for 86 MB more resident memory; the memory of a program that does not
         * allocate that much is not touched. GC_INITIAL_HEAP_SIZE in the environment, which the collector reads itself,
         * takes precedence. */
        if (getenv("GC_INITIAL_HEAP_SIZE") == NULL) {
            GC_expand_hp((size_t) 256 << 20);
        }
    }
}

__attribute__((constructor(101))) static void nabu_boehm_init(void) {
    nabu_gc_init();
}

/* NABU_GC_STATS=1: one line on stderr per collection (number, duration, heap size, live bytes after it). */
static double nabu_gc_now_ms(void) {
#ifdef _WIN32
    LARGE_INTEGER f, c;
    QueryPerformanceFrequency(&f);
    QueryPerformanceCounter(&c);
    return (double) c.QuadPart * 1000.0 / (double) f.QuadPart;
#else
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (double) ts.tv_sec * 1000.0 + (double) ts.tv_nsec / 1e6;
#endif
}
extern size_t GC_get_prof_stats_unsafe(struct GC_prof_stats_s *, size_t); /* gc.h declares it only with GC_THREADS */
static double nabu_gc_started_ms = 0;
static int nabu_gc_stats_on = 0;

/* An allocation profile (a build with -Dnabu.allocProfile=true: the compiled code then allocates through
 * nabu_prof_malloc*, below): bytes and objects per call site, the return address of the allocation. With
 * NABU_ALLOC_PROF=<file> the table is written to that file at every fourth collection, as "offset bytes count" lines (the
 * offset from the start of the executable; symbolize with llvm-nm -n). */
#define NABU_PROF_SLOTS 65536
typedef struct { uintptr_t site; uint64_t bytes; uint64_t count; } nabu_prof_entry;
static nabu_prof_entry nabu_prof_table[NABU_PROF_SLOTS];
static const char *nabu_prof_path = NULL;

static void nabu_prof_record(void *site, size_t n) {
    const uintptr_t key = (uintptr_t) site;
    size_t i = (size_t) ((key >> 2) * 0x9E3779B97F4A7C15ull >> 48) & (NABU_PROF_SLOTS - 1);
    for (int probes = 0; probes < NABU_PROF_SLOTS; probes++, i = (i + 1) & (NABU_PROF_SLOTS - 1)) {
        uintptr_t seen = __atomic_load_n(&nabu_prof_table[i].site, __ATOMIC_RELAXED);
        if (seen == 0 && __atomic_compare_exchange_n(&nabu_prof_table[i].site, &seen, key, 0, __ATOMIC_RELAXED, __ATOMIC_RELAXED)) seen = key;
        if (seen == key) {
            __atomic_fetch_add(&nabu_prof_table[i].bytes, (uint64_t) n, __ATOMIC_RELAXED);
            __atomic_fetch_add(&nabu_prof_table[i].count, 1, __ATOMIC_RELAXED);
            return;
        }
    }
}

/* NABU_ALLOC_GLOBAL=1 (for a build with -Dnabu.allocProfile=true): allocate without the thread-local free lists of the
 * collector, to tell whether those are the cause of an object that is handed out twice. */
extern void *GC_malloc_kind_global(size_t n, int kind);
static int nabu_alloc_global_flag(void) {
    static volatile int known = -1;
    if (known < 0) known = getenv("NABU_ALLOC_GLOBAL") != NULL;
    return known;
}

void *nabu_prof_malloc(size_t n) {
    nabu_prof_record(__builtin_return_address(0), n);
    return nabu_alloc_global_flag() ? GC_malloc_kind_global(n, 1) : GC_malloc(n);
}

void *nabu_prof_malloc_atomic(size_t n) {
    nabu_prof_record(__builtin_return_address(0), n);
    return nabu_alloc_global_flag() ? GC_malloc_kind_global(n, 0) : GC_malloc_atomic(n);
}

/* The allocations of the C runtime itself (nabu_gc_alloc: strings, arrays, reflection objects, boxes made in C), counted
 * while NABU_ALLOC_PROF is set. The function that asks is usually a small static helper, so the key is the call chain:
 * the return addresses of the six frames above the allocation, from which a reader takes the first one that is a
 * compiled method or a function of the class library. They are written as "R f1 .. f6 bytes count" lines. */
#define NABU_RPROF_SLOTS 16384
#define NABU_RPROF_FRAMES 6
typedef struct { uintptr_t frames[NABU_RPROF_FRAMES]; uint64_t bytes; uint64_t count; } nabu_rprof_entry;
static nabu_rprof_entry nabu_rprof_table[NABU_RPROF_SLOTS];
static volatile char nabu_rprof_busy = 0;

typedef struct { uintptr_t frames[NABU_RPROF_FRAMES]; int skip; int count; } nabu_rprof_walk;

static _Unwind_Reason_Code nabu_rprof_frame(struct _Unwind_Context *context, void *arg) {
    nabu_rprof_walk *walk = (nabu_rprof_walk *) arg;
    if (walk->skip > 0) { walk->skip--; return _URC_NO_REASON; }
    if (walk->count >= NABU_RPROF_FRAMES) return _URC_END_OF_STACK;
    walk->frames[walk->count++] = (uintptr_t) _Unwind_GetIP(context);
    return _URC_NO_REASON;
}

static void nabu_rprof_record(size_t n) {
    nabu_rprof_walk walk;
    memset(&walk, 0, sizeof walk);
    walk.skip = 2;   /* this function and nabu_gc_alloc */
    _Unwind_Backtrace(nabu_rprof_frame, &walk);
    uintptr_t h = 1469598103934665603ull;
    for (int i = 0; i < NABU_RPROF_FRAMES; i++) h = (h ^ walk.frames[i]) * 1099511628211ull;
    while (__atomic_test_and_set(&nabu_rprof_busy, __ATOMIC_ACQUIRE)) { }
    size_t slot = (size_t) (h >> 20) & (NABU_RPROF_SLOTS - 1);
    for (int probes = 0; probes < NABU_RPROF_SLOTS; probes++, slot = (slot + 1) & (NABU_RPROF_SLOTS - 1)) {
        nabu_rprof_entry *e = &nabu_rprof_table[slot];
        if (e->count == 0) memcpy(e->frames, walk.frames, sizeof e->frames);
        else if (memcmp(e->frames, walk.frames, sizeof e->frames) != 0) continue;
        e->bytes += n;
        e->count++;
        break;
    }
    __atomic_clear(&nabu_rprof_busy, __ATOMIC_RELEASE);
}

static void nabu_prof_dump(void) {
#ifdef _WIN32
    const uintptr_t base = (uintptr_t) GetModuleHandleA(NULL);
    HANDLE f = CreateFileA(nabu_prof_path, GENERIC_WRITE, FILE_SHARE_READ, NULL, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
    if (f == INVALID_HANDLE_VALUE) return;
#else
    extern const char __executable_start;
    const uintptr_t base = (uintptr_t) &__executable_start;
    const int f = open(nabu_prof_path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (f < 0) return;
#endif
    char line[96];
    for (int i = 0; i < NABU_PROF_SLOTS; i++) {
        if (nabu_prof_table[i].site == 0) continue;
        const int n = snprintf(line, sizeof line, "%llx %llu %llu\n", (unsigned long long) (nabu_prof_table[i].site - base),
                (unsigned long long) nabu_prof_table[i].bytes, (unsigned long long) nabu_prof_table[i].count);
#ifdef _WIN32
        DWORD written;
        WriteFile(f, line, (DWORD) n, &written, NULL);
#else
        if (write(f, line, (size_t) n) < 0) break;
#endif
    }
    for (int i = 0; i < NABU_RPROF_SLOTS; i++) {
        const nabu_rprof_entry *e = &nabu_rprof_table[i];
        if (e->count == 0) continue;
        char wide[256];
        int n = snprintf(wide, sizeof wide, "R");
        for (int k = 0; k < NABU_RPROF_FRAMES; k++) {
            n += snprintf(wide + n, sizeof wide - (size_t) n, " %llx",
                    (unsigned long long) (e->frames[k] != 0 ? e->frames[k] - base : 0));
        }
        n += snprintf(wide + n, sizeof wide - (size_t) n, " %llu %llu\n", (unsigned long long) e->bytes, (unsigned long long) e->count);
#ifdef _WIN32
        DWORD written;
        WriteFile(f, wide, (DWORD) n, &written, NULL);
#else
        if (write(f, wide, (size_t) n) < 0) break;
#endif
    }
#ifdef _WIN32
    CloseHandle(f);
#else
    close(f);
#endif
}

static void nabu_gc_on_event(GC_EventType e) {
    if (e == GC_EVENT_START) {
        nabu_gc_started_ms = nabu_gc_now_ms();
    } else if (e == GC_EVENT_END) {
        if (nabu_prof_path != NULL && GC_get_gc_no() % 4 == 0) nabu_prof_dump();
        if (!nabu_gc_stats_on) return;
        struct GC_prof_stats_s s;
        GC_get_prof_stats_unsafe(&s, sizeof s); /* the collector holds its lock in the callback */
        /* No stdio here: the world is stopped, and a thread that holds the lock of stderr would never let go of it. */
        char line[200];
        const int n = snprintf(line, sizeof line,
                "[nabu/gc] #%lu %.1f ms heap %lu MB, allocated before %lu MB, reclaimed %lu MB\n",
                (unsigned long) s.gc_no, nabu_gc_now_ms() - nabu_gc_started_ms,
                (unsigned long) (s.heapsize_full >> 20), (unsigned long) (s.allocd_bytes_before_gc >> 20),
                (unsigned long) (s.reclaimed_bytes_before_gc >> 20));
#ifdef _WIN32
        DWORD written;
        WriteFile(GetStdHandle(STD_ERROR_HANDLE), line, (DWORD) n, &written, NULL);
#else
        if (write(2, line, (size_t) n) < 0) { /* nothing to do */ }
#endif
    }
}
__attribute__((constructor(103))) static void nabu_gc_stats_init(void) {
    nabu_gc_stats_on = getenv("NABU_GC_STATS") != NULL;
    nabu_prof_path = getenv("NABU_ALLOC_PROF");
    if (nabu_gc_stats_on || nabu_prof_path != NULL) GC_set_on_collection_event(nabu_gc_on_event);
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

void *nabu_gc_alloc(size_t n) {
    nabu_gc_alloc_bytes += n;
    if (nabu_prof_path != NULL) nabu_rprof_record(n);
    return GC_malloc(n);
}
void *nabu_gc_alloc_atomic(size_t n) {
    nabu_gc_alloc_bytes += n;
    if (nabu_prof_path != NULL) nabu_rprof_record(n);
    return GC_malloc_atomic(n);
}
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

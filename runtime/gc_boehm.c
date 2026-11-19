/*
 * The Boehm-Demers-Weiser collector behind the interface of nabu_gc.h. Compiled with -DNABU_GC_BOEHM and linked with
 * -lgc. The compiled code calls GC_malloc and GC_malloc_atomic itself.
 */
#ifndef NABU_GC_BOEHM
#error "gc_boehm.c needs -DNABU_GC_BOEHM"
#endif

#include <pthread.h>
#ifdef _WIN32
#include <windows.h>
#else
#include <fcntl.h>
#include <sys/syscall.h>
#include <unistd.h>
#endif
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include <unwind.h>

#include <gc.h>
#include <gc/gc_mark.h>
#include "nabu_gc.h"

/* the soft references (further down) are set up by nabu_gc_init */
extern void GC_push_all_eager(void *bottom, void *top);
extern void (*GC_get_push_other_roots(void))(void);
extern void GC_set_push_other_roots(void (*proc)(void));
static void nabu_soft_push_roots(void);
static unsigned long nabu_soft_every;
static size_t nabu_soft_heap_limit;
static void (*nabu_soft_old_roots)(void);

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
        {
            const char *every = getenv("NABU_SOFT_REF_EVERY");
            if (every != NULL && atol(every) > 0) nabu_soft_every = (unsigned long) atol(every);
            nabu_soft_heap_limit = GC_get_heap_size() + GC_get_heap_size() / 2;
            nabu_soft_old_roots = GC_get_push_other_roots();
            GC_set_push_other_roots(nabu_soft_push_roots);
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

/* NABU_GC_CENSUS=<n>: at the end of the mark phase of collection number n (and of every later one when the number is
 * followed by a "+"), a census of the live objects on stderr: per type the number of objects and the bytes that the
 * collector holds for them, the top NABU_GC_CENSUS_TOP (default 40). The type of an object is the first word of it
 * (the type info of the compiled code); objects that do not start with a pointer into the executable (arrays of the
 * runtime, structures of the C code) are counted as "other" per size class of 16 bytes. Meant to find what the live
 * data of a program consists of, before any change to the layout of objects. */
#define NABU_CENSUS_SLOTS 32768
typedef struct { uintptr_t key; const void *sample; uint64_t bytes; uint64_t count; } nabu_census_entry;
static nabu_census_entry nabu_census_table[NABU_CENSUS_SLOTS];
static uint64_t nabu_census_total_bytes, nabu_census_total_count, nabu_census_other_bytes, nabu_census_other_count;
static long nabu_census_at = 0;
static int nabu_census_repeat = 0;
static int nabu_census_top = 40;
extern uintptr_t nabu_image_lo, nabu_image_size;
extern const char *nabu_compiled_type_name(const void *p);
extern const char *nabu_compiled_type_name(const void *p);

static void nabu_census_visit(void *obj, size_t bytes, void *client) {
    (void) client;
    nabu_census_total_bytes += bytes;
    nabu_census_total_count++;
    const uintptr_t first = ((const uintptr_t *) obj)[0];
    uintptr_t key;
    if (nabu_image_size != 0 && first - nabu_image_lo < nabu_image_size) {
        key = first;
    } else {
        nabu_census_other_bytes += bytes;
        nabu_census_other_count++;
        key = 1 + (bytes >> 4);            /* "other": by size class, a small number cannot be an address */
    }
    size_t i = (size_t) ((key >> 3) * 0x9E3779B97F4A7C15ull >> 48) & (NABU_CENSUS_SLOTS - 1);
    for (int probes = 0; probes < NABU_CENSUS_SLOTS; probes++, i = (i + 1) & (NABU_CENSUS_SLOTS - 1)) {
        if (nabu_census_table[i].key == 0) {
            nabu_census_table[i].key = key;
            nabu_census_table[i].sample = obj;
        }
        if (nabu_census_table[i].key == key) {
            nabu_census_table[i].bytes += bytes;
            nabu_census_table[i].count++;
            return;
        }
    }
}

static void nabu_census_write(const char *line, int n) {
#ifdef _WIN32
    DWORD written;
    WriteFile(GetStdHandle(STD_ERROR_HANDLE), line, (DWORD) n, &written, NULL);
#else
    if (write(2, line, (size_t) n) < 0) { /* nothing to do */ }
#endif
}

static void nabu_census_report(unsigned long gc_no) {
    memset(nabu_census_table, 0, sizeof nabu_census_table);
    nabu_census_total_bytes = nabu_census_total_count = nabu_census_other_bytes = nabu_census_other_count = 0;
    GC_enumerate_reachable_objects_inner(nabu_census_visit, NULL);   /* the collector holds its lock in the callback */
    char line[400];
    int n = snprintf(line, sizeof line, "[nabu/census] collection #%lu: %llu objects, %.1f MB live (%.1f MB of it in "
            "objects without a type of the compiled code: %llu objects)\n", gc_no,
            (unsigned long long) nabu_census_total_count, (double) nabu_census_total_bytes / 1048576.0,
            (double) nabu_census_other_bytes / 1048576.0, (unsigned long long) nabu_census_other_count);
    nabu_census_write(line, n);
    const int top = nabu_census_top;   /* read at start-up: no getenv while the world is stopped (a lock of the C library) */
    /* the largest entries, by selection (the table is small) */
    for (int rank = 0; rank < top; rank++) {
        long best = -1;
        for (long i = 0; i < NABU_CENSUS_SLOTS; i++) {
            if (nabu_census_table[i].key != 0 && nabu_census_table[i].bytes != 0
                    && (best < 0 || nabu_census_table[i].bytes > nabu_census_table[best].bytes)) best = i;
        }
        if (best < 0) break;
        nabu_census_entry *e = &nabu_census_table[best];
        const char *name = NULL;
        if (e->key - nabu_image_lo < nabu_image_size) name = nabu_compiled_type_name(e->sample);
        if (name != NULL) {
            n = snprintf(line, sizeof line, "[nabu/census] %8.2f MB %9llu objects %6.1f B each  %s\n",
                    (double) e->bytes / 1048576.0, (unsigned long long) e->count,
                    (double) e->bytes / (double) e->count, name);
        } else {
            n = snprintf(line, sizeof line, "[nabu/census] %8.2f MB %9llu objects %6.1f B each  (other, blocks of %llu bytes)\n",
                    (double) e->bytes / 1048576.0, (unsigned long long) e->count,
                    (double) e->bytes / (double) e->count, (unsigned long long) ((e->key - 1) << 4));
        }
        nabu_census_write(line, n);
        e->bytes = 0;
    }
}

static void nabu_gc_on_event(GC_EventType e) {
    if (e == GC_EVENT_MARK_END && nabu_census_at > 0) {
        const unsigned long no = (unsigned long) GC_get_gc_no();
        if (no == (unsigned long) nabu_census_at || (nabu_census_repeat && no > (unsigned long) nabu_census_at)) {
            nabu_census_report(no);
        }
        return;
    }
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
    const char *census = getenv("NABU_GC_CENSUS");
    if (census != NULL && atol(census) > 0) {
        nabu_census_at = atol(census);
        nabu_census_repeat = strchr(census, '+') != NULL;
        const char *topEnv = getenv("NABU_GC_CENSUS_TOP");
        if (topEnv != NULL && atoi(topEnv) > 0) nabu_census_top = atoi(topEnv);
    }
    if (nabu_gc_stats_on || nabu_prof_path != NULL || nabu_census_at > 0) GC_set_on_collection_event(nabu_gc_on_event);
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

/* ---- weak pointers ----
 * A weak slot holds the bitwise complement of the pointer: it has its top bit set (a pointer of a program is below 2^63),
 * so the collector does not take it for a pointer, and it can be told from a plain pointer. The collector clears the slot
 * (GC_general_register_disappearing_link) when the object is not reachable any more, before anything is finalized. */
int nabu_gc_weak_set(void **slot, void *obj) {
    /* only the first word of an object of the collector can be registered: an object of the executable's image or on
     * the stack never dies anyway, and stays a plain pointer */
    if (obj == NULL || GC_base(obj) != obj) return 0;
    *slot = (void *) ~(uintptr_t) obj;
    const int r = GC_general_register_disappearing_link(slot, obj);
    if (r == GC_SUCCESS || r == GC_DUPLICATE) return 1;
    *slot = obj;
    return 0;
}

typedef struct { void **slot; void *result; } nabu_weak_read;
static void *nabu_weak_read_locked(void *arg) {
    nabu_weak_read *r = (nabu_weak_read *) arg;
    void *v = *r->slot;
    r->result = (v != NULL && (intptr_t) v < 0) ? (void *) ~(uintptr_t) v : v;
    return NULL;
}
void *nabu_gc_weak_get(void **slot) {
    void *v = __atomic_load_n(slot, __ATOMIC_ACQUIRE);
    if ((intptr_t) v >= 0) return v;             /* cleared, or a plain pointer */
    /* A collection can clear the slot between reading the hidden value and the pointer going to a place where the
     * collector sees it: the thread would use a pointer to an object that was freed. So the pointer first goes to the
     * stack, which the collector scans, and then the slot is read again: still the same value means that the object was
     * alive when the pointer became visible (a collection before that would have cleared the slot). No lock of the
     * collector: this is called for every get() of every weak and soft reference. */
    void *volatile guard = (void *) ~(uintptr_t) v;
    __atomic_thread_fence(__ATOMIC_SEQ_CST);
    if (__atomic_load_n(slot, __ATOMIC_ACQUIRE) == v) return guard;
    nabu_weak_read r = { slot, NULL };           /* changed in the meantime: the slow way, under the lock */
    GC_call_with_alloc_lock(nabu_weak_read_locked, &r);
    return r.result;
}

void nabu_gc_weak_clear(void **slot) {
    GC_unregister_disappearing_link(slot);
    *slot = NULL;
}

/* The weak and soft references that have to be watched: a soft reference (its referent is kept as a root, see below) and a
 * reference with a queue (it is put on the queue after the collector cleared the referent). One entry per reference, holding
 * the reference hidden, and the collector clears that when the reference itself dies, so the entry does not keep it alive. */
#define NABU_REF_SOFT 1
#define NABU_REF_QUEUED 2
typedef struct nabu_weak_entry {
    uintptr_t hidden_ref;
    void **slot;
    struct nabu_weak_entry *next;
    int flags;
} nabu_weak_entry;
static nabu_weak_entry *nabu_ref_tracked = NULL;    /* watched */
static nabu_weak_entry *nabu_weak_ready = NULL;     /* with a queue, slot cleared, not yet handed out */
static unsigned long nabu_weak_count = 0, nabu_weak_purge_at = 8192, nabu_weak_scanned_gc = 0;
static pthread_mutex_t nabu_weak_mutex = PTHREAD_MUTEX_INITIALIZER;

static void *nabu_weak_entry_ref(nabu_weak_entry *e) {
    nabu_weak_read r = { (void **) &e->hidden_ref, NULL };
    GC_call_with_alloc_lock(nabu_weak_read_locked, &r);
    return r.result;
}

/* Walks the watched entries after a collection: an entry whose reference died is dropped, one with a queue whose slot was
 * cleared goes to the ready list. Under nabu_weak_mutex. */
static void nabu_weak_scan(void) {
    nabu_weak_entry **link = &nabu_ref_tracked;
    while (*link != NULL) {
        nabu_weak_entry *e = *link;
        if (e->hidden_ref == 0) {                    /* the reference died: the collector cleared the entry */
            *link = e->next;
            free(e);
            nabu_weak_count--;
        } else if ((e->flags & NABU_REF_QUEUED) != 0 && *e->slot == NULL) {   /* the referent was cleared */
            *link = e->next;
            e->next = nabu_weak_ready;
            nabu_weak_ready = e;
        } else {
            link = &e->next;
        }
    }
    nabu_weak_purge_at = nabu_weak_count * 2 + 8192;
}

/* Remembers a reference whose slot is a weak pointer (nabu_gc_weak_set): soft, and/or with a queue. */
void nabu_gc_ref_track(void *ref, void **slot, int soft, int queued) {
    if (!soft && !queued) return;
    nabu_weak_entry *e = (nabu_weak_entry *) malloc(sizeof *e);
    if (e == NULL) return;
    e->hidden_ref = ~(uintptr_t) ref;
    e->slot = slot;
    e->flags = (soft ? NABU_REF_SOFT : 0) | (queued ? NABU_REF_QUEUED : 0);
    if (GC_base(ref) != ref || GC_general_register_disappearing_link((void **) &e->hidden_ref, ref) > GC_DUPLICATE) {
        free(e);
        return;
    }
    pthread_mutex_lock(&nabu_weak_mutex);
    e->next = nabu_ref_tracked;
    nabu_ref_tracked = e;
    nabu_weak_count++;
    /* References that nobody polls the queue of would pile up: drop those that died, now and then */
    if (nabu_weak_count >= nabu_weak_purge_at && GC_get_gc_no() != nabu_weak_scanned_gc) {
        nabu_weak_scan();
        nabu_weak_scanned_gc = GC_get_gc_no();
    }
    pthread_mutex_unlock(&nabu_weak_mutex);
}

/* ---- soft references ----
 * The referents of the soft references are kept by the collector: before the marking they are pushed as roots. Not in every
 * NABU_SOFT_REF_EVERY-th collection, and not when the heap has grown to more than 1.5 times its initial size: then a
 * referent that nothing else refers to is cleared (the pressure a JVM clears soft references under). A cache made of soft
 * references then lives through most collections and stays bounded. */
static unsigned long nabu_soft_every = 8;
static size_t nabu_soft_heap_limit = 0;
static void (*nabu_soft_old_roots)(void) = NULL;

/* Runs in the collector, with the world stopped. */
static void nabu_soft_push_roots(void) {
    const int pressure = (GC_get_gc_no() + 1) % nabu_soft_every == 0
            || (nabu_soft_heap_limit != 0 && GC_get_heap_size() > nabu_soft_heap_limit);
    if (!pressure) {
        for (nabu_weak_entry *e = nabu_ref_tracked; e != NULL; e = e->next) {
            if (e->hidden_ref == 0 || (e->flags & NABU_REF_SOFT) == 0) continue;   /* died, or not soft */
            void *v = *e->slot;
            if ((intptr_t) v < 0) {
                v = (void *) ~(uintptr_t) v;
                GC_push_all_eager(&v, &v + 1);
            }
        }
    }
    if (nabu_soft_old_roots != NULL) nabu_soft_old_roots();
}

void *nabu_gc_weak_next_cleared(void) {
    void *ref = NULL;
    pthread_mutex_lock(&nabu_weak_mutex);
    if (GC_get_gc_no() != nabu_weak_scanned_gc) {
        nabu_weak_scan();
        nabu_weak_scanned_gc = GC_get_gc_no();
    }
    while (nabu_weak_ready != NULL && ref == NULL) {
        nabu_weak_entry *e = nabu_weak_ready;
        nabu_weak_ready = e->next;
        ref = nabu_weak_entry_ref(e);                /* NULL when the reference died in the meantime */
        GC_unregister_disappearing_link((void **) &e->hidden_ref);
        free(e);
        nabu_weak_count--;
    }
    pthread_mutex_unlock(&nabu_weak_mutex);
    return ref;
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
NABU_TLS_VAR(struct GC_ms_entry *, nabu_boehm_msp, NULL)
#define nabu_boehm_msp NABU_TLS(nabu_boehm_msp)
NABU_TLS_VAR(struct GC_ms_entry *, nabu_boehm_limit, NULL)
#define nabu_boehm_limit NABU_TLS(nabu_boehm_limit)

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

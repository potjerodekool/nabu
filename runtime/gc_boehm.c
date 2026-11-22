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
#include <sys/mman.h>
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
         * collects over and over while all of it is still alive (the petstore: 2.7 -> 2.1 s to start). The heap is then
         * mostly garbage between two collections, and that is what is resident: 256 MB was chosen when the weak and soft
         * references were strong and the live data of the petstore grew by itself (which made every collection longer,
         * and a small heap expensive). With those fixed the live data stays at 25 to 55 MB, and 128 MB serves the same
         * number of requests per second (a run of 20000 requests per endpoint: 1586/1201/1133 against 1599/1207/1143
         * with 256 MB) in 275 MB resident instead of 403 MB; 96 MB: 240 MB, 64 MB: 205 MB and up to 5% slower. The memory
         * of a program that does not allocate that much is not touched. GC_INITIAL_HEAP_SIZE in the environment, which
         * the collector reads itself, takes precedence. */
        if (getenv("GC_INITIAL_HEAP_SIZE") == NULL) {
            GC_expand_hp((size_t) 128 << 20);
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

/* A build with -Dnabu.gc.debugAlloc=true allocates through the debug allocator of the collector: every object gets guard
 * words around it, and at every collection the collector reports the objects whose guard was overwritten ("GC_check_heap
 * ... found smashed heap objects") with the "line" that was given at the allocation: here the offset of the caller in
 * the executable (symbolize with llvm-nm -n, the offset is added to the base of the image). A write past the end of an
 * object, which the rounding of the block size hides in a normal build, shows up as a smashed object. */
extern void *GC_debug_malloc(size_t n, const char *file, int line);
extern void *GC_debug_malloc_atomic(size_t n, const char *file, int line);
extern uintptr_t nabu_image_lo;
void *nabu_dbg_malloc(size_t n) __attribute__((noinline));
void *nabu_dbg_malloc(size_t n) {
    return GC_debug_malloc(n, "nabu", (int) ((uintptr_t) __builtin_return_address(0) - nabu_image_lo));
}
void *nabu_dbg_malloc_atomic(size_t n) __attribute__((noinline));
void *nabu_dbg_malloc_atomic(size_t n) {
    return GC_debug_malloc_atomic(n, "nabu", (int) ((uintptr_t) __builtin_return_address(0) - nabu_image_lo));
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
#ifdef __APPLE__
    extern const char _mh_execute_header;               /* macOS has no __executable_start: the Mach-O header */
    const uintptr_t base = (uintptr_t) &_mh_execute_header;
#else
    extern const char __executable_start;
    const uintptr_t base = (uintptr_t) &__executable_start;
#endif
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
extern const char *nabu_array_type_name(int32_t id);
extern int nabu_jcl_is_object(const void *p);
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

/* NABU_CARD_STATS=1 (with the card marking of the compiler): at every mark end, how many cards were set by a store since the
 * previous mark end, and how many of them lie in an object that was already live at the previous mark end (an "old" object:
 * the cards that a nursery collection would have to scan; stores into new objects need no scanning). */
extern unsigned char nabu_card_table[1 << 24];
static int nabu_card_stats_on = 0;
static uintptr_t nabu_old_base = 0, nabu_card_base = 0;
static int nabu_old_valid = 0;
static uint64_t nabu_old_bits[1 << 20];   /* one bit per 16 bytes of a window of 1 GB: live at the previous mark end */
static uint64_t nabu_old2_bits[1 << 20];  /* live at the previous two mark ends: "old" after one aging step */

static uint64_t nabu_prev_bits[1 << 20];  /* nabu_old_bits of the previous mark end */
static uint64_t nabu_live_bytes, nabu_young_bytes, nabu_young_objects, nabu_live_objects;

static void nabu_card_visit(void *obj, size_t bytes, void *client) {
    (void) client;
    const uintptr_t a = (uintptr_t) obj;
    if (nabu_old_base != 0 && a >= nabu_old_base && a - nabu_old_base < ((uintptr_t) 1 << 30)) {
        const uintptr_t g0 = (a - nabu_old_base) >> 4;
        nabu_live_bytes += bytes;
        nabu_live_objects++;
        if (!((nabu_prev_bits[g0 >> 6] >> (g0 & 63)) & 1)) {
            nabu_young_bytes += bytes;
            nabu_young_objects++;
        }
    }
    if (nabu_old_base == 0) {
        nabu_old_base = a & ~(((uintptr_t) 1 << 30) - 1);
        nabu_card_base = a & ~(((uintptr_t) 1 << 33) - 1);
    }
    if (a < nabu_old_base || a - nabu_old_base >= ((uintptr_t) 1 << 30)) return;
    for (uintptr_t g = (a - nabu_old_base) >> 4, e = (a - nabu_old_base + bytes + 15) >> 4; g < e && g < ((uintptr_t) 1 << 26); g++) {
        nabu_old_bits[g >> 6] |= (uint64_t) 1 << (g & 63);
    }
}

static void nabu_card_mark_end(unsigned long gc_no) {
    unsigned long total = 0, old = 0, old2 = 0;
    if (nabu_old_valid) {
        for (size_t i = 0; i < sizeof nabu_card_table; i++) {
            if (!nabu_card_table[i]) continue;
            nabu_card_table[i] = 0;
            total++;
            /* a card is one 16-byte granule of a window of 256 MB that wraps: try the four windows of the 1 GB that holds the heap */
            int in_old = 0, in_old2 = 0;
            for (uintptr_t k = 0; k < 4; k++) {
                const uintptr_t addr = nabu_old_base + (k << 28) + ((uintptr_t) i << 4);
                const uintptr_t g = (addr - nabu_old_base) >> 4;
                if ((nabu_old_bits[g >> 6] >> (g & 63)) & 1) in_old = 1;
                if ((nabu_old2_bits[g >> 6] >> (g & 63)) & 1) in_old2 = 1;
            }
            old += in_old;
            old2 += in_old2;
        }
    } else {
        memset(nabu_card_table, 0, sizeof nabu_card_table);
    }
    uint64_t *const previous = nabu_prev_bits;
    memcpy(previous, nabu_old_bits, sizeof nabu_prev_bits);
    memset(nabu_old_bits, 0, sizeof nabu_old_bits);
    nabu_live_bytes = nabu_young_bytes = nabu_young_objects = nabu_live_objects = 0;
    GC_enumerate_reachable_objects_inner(nabu_card_visit, NULL);
    for (size_t w = 0; w < (1 << 20); w++) nabu_old2_bits[w] = nabu_old_bits[w] & previous[w];
    static size_t last_total = 0;
    const size_t total_now = GC_get_total_bytes();
    if (nabu_old_valid) {
        char sline[400];
        const size_t allocated = total_now - last_total;
        const int sn = snprintf(sline, sizeof sline, "[survival] collection %lu: allocated %lu KB since the last, live %lu KB (%lu objects), new and still live %lu KB (%lu objects) = %.1f%% of the allocation\n",
                gc_no, (unsigned long) (allocated / 1024), (unsigned long) (nabu_live_bytes / 1024), (unsigned long) nabu_live_objects,
                (unsigned long) (nabu_young_bytes / 1024), (unsigned long) nabu_young_objects,
                allocated ? 100.0 * (double) nabu_young_bytes / (double) allocated : 0.0);
        nabu_census_write(sline, sn);
    }
    last_total = total_now;
    nabu_old_valid = 1;
    char line[320];
    const int n = snprintf(line, sizeof line, "[cards] collection %lu: %lu cards set since the last one, %lu in objects live at the previous mark end, %lu KB of them; %lu in objects live at the previous two, %lu KB"
            "\n", gc_no, total, old, old * 16 / 1024, old2, old2 * 16 / 1024);
    nabu_census_write(line, n);
}

/* NABU_GC_VERIFY=1 (stage 0 of docs/gc-generational-design.md): at every mark end, check the pointer maps of the compiled classes
 * (slots 10 and 11 of nabu_type_info) against the heap. For every reachable object whose header points at a type info of the
 * image, every word of the instance that is NOT in the map and holds a pointer into a live heap object is reported by class
 * and offset: a reference field that the compiler left out of the map (or a long that holds an address). A precise collector
 * would not update such a word; an empty report means the map is complete for the objects of this run. */
typedef struct {
    const char *name; size_t size; const void *super_type; int field_count; const char *const *interfaces;
    int interface_count; const void *instance_fields; int instance_field_count; void **vtable; void **itable;
    const int32_t *ref_offsets; int ref_count;
} nabu_gc_type_info;

static int nabu_verify_on = 0;
static uint64_t nabu_verify_bits[1 << 20];   /* one bit per 16 bytes of a window of 1 GB: covered by a reachable object */
static uintptr_t nabu_verify_base = 0;
typedef struct { const nabu_gc_type_info *ti; uint32_t offset; unsigned long count; } nabu_verify_hit;
#define NABU_VERIFY_HITS 8192
static nabu_verify_hit nabu_verify_hits[NABU_VERIFY_HITS];
static unsigned long nabu_verify_objects, nabu_verify_typed, nabu_verify_words, nabu_verify_mapped;

static void nabu_verify_cover(void *obj, size_t bytes, void *client) {
    (void) client;
    const uintptr_t a = (uintptr_t) obj;
    if (nabu_verify_base == 0) nabu_verify_base = a & ~(((uintptr_t) 1 << 30) - 1);
    if (a < nabu_verify_base || a - nabu_verify_base >= ((uintptr_t) 1 << 30)) return;
    for (uintptr_t g = (a - nabu_verify_base) >> 4, e = (a - nabu_verify_base + bytes + 15) >> 4; g < e && g < ((uintptr_t) 1 << 26); g++) {
        nabu_verify_bits[g >> 6] |= (uint64_t) 1 << (g & 63);
    }
}

static int nabu_verify_is_heap(uintptr_t v) {
    if (v < nabu_verify_base || v - nabu_verify_base >= ((uintptr_t) 1 << 30)) return 0;
    const uintptr_t g = (v - nabu_verify_base) >> 4;
    return (int) ((nabu_verify_bits[g >> 6] >> (g & 63)) & 1);
}

static void nabu_verify_check(void *obj, size_t bytes, void *client) {
    (void) client;
    nabu_verify_objects++;
    const uintptr_t first = ((const uintptr_t *) obj)[0];
    if (nabu_image_size == 0 || first - nabu_image_lo >= nabu_image_size || (first & 7) != 0) return;
    const nabu_gc_type_info *ti = (const nabu_gc_type_info *) first;
    /* is it a type info? its name and super type point into the image or are null, its size fits the block */
    if ((uintptr_t) ti->name - nabu_image_lo >= nabu_image_size) return;
    if (ti->size < 16 || ti->size > bytes || ti->size > (1 << 20)) return;
    if (ti->super_type != NULL && (uintptr_t) ti->super_type - nabu_image_lo >= nabu_image_size) return;
    nabu_verify_typed++;
    const uintptr_t *words = (const uintptr_t *) obj;
    for (size_t off = 16; off + 8 <= ti->size; off += 8) {
        int mapped = 0;
        for (int i = 0; i < ti->ref_count; i++) {
            if ((size_t) ti->ref_offsets[i] == off) { mapped = 1; break; }
        }
        nabu_verify_words++;
        if (mapped) { nabu_verify_mapped++; continue; }
        if (!nabu_verify_is_heap(words[off / 8])) continue;
        size_t slot = (((uintptr_t) ti >> 4) * 31 + off) % NABU_VERIFY_HITS;
        for (size_t probe = 0; probe < NABU_VERIFY_HITS; probe++, slot = (slot + 1) % NABU_VERIFY_HITS) {
            nabu_verify_hit *h = &nabu_verify_hits[slot];
            if (h->ti == NULL) { h->ti = ti; h->offset = (uint32_t) off; h->count = 1; break; }
            if (h->ti == ti && h->offset == off) { h->count++; break; }
        }
    }
}

static void nabu_verify_mark_end(unsigned long gc_no) {
    memset(nabu_verify_bits, 0, sizeof nabu_verify_bits);
    memset(nabu_verify_hits, 0, sizeof nabu_verify_hits);
    nabu_verify_objects = nabu_verify_typed = nabu_verify_words = nabu_verify_mapped = 0;
    GC_enumerate_reachable_objects_inner(nabu_verify_cover, NULL);
    GC_enumerate_reachable_objects_inner(nabu_verify_check, NULL);
    unsigned long unmapped = 0, sites = 0;
    for (size_t i = 0; i < NABU_VERIFY_HITS; i++) {
        if (nabu_verify_hits[i].ti != NULL) { unmapped += nabu_verify_hits[i].count; sites++; }
    }
    char line[400];
    int n = snprintf(line, sizeof line, "[nabu/verify] collection #%lu: %lu reachable objects, %lu with a type info of the image, "
            "%lu words checked (%lu in a pointer map), %lu words outside the map point into live objects, at %lu fields\n",
            gc_no, nabu_verify_objects, nabu_verify_typed, nabu_verify_words, nabu_verify_mapped, unmapped, sites);
    nabu_census_write(line, n);
    for (int rank = 0; rank < 25; rank++) {
        long best = -1;
        for (long i = 0; i < NABU_VERIFY_HITS; i++) {
            if (nabu_verify_hits[i].ti != NULL && nabu_verify_hits[i].count != 0
                    && (best < 0 || nabu_verify_hits[i].count > nabu_verify_hits[best].count)) best = i;
        }
        if (best < 0) break;
        n = snprintf(line, sizeof line, "[nabu/verify]   %8lu x  %s +%u\n", nabu_verify_hits[best].count,
                nabu_verify_hits[best].ti->name, nabu_verify_hits[best].offset);
        nabu_census_write(line, n);
        nabu_verify_hits[best].count = 0;
    }
}

/* NABU_CARD_STATS=1, second half (stage 3 of docs/gc-generational-design.md): how many of the young objects (live now, not
 * live at the previous mark end) a mostly-copying nursery collection would have to pin, because some word that is not a
 * precisely known reference points into them: a word of a reachable object outside its pointer map (all words of a block
 * that is not a compiled object or a reference array), a word of the executable image (statics), a word of the stack of
 * the thread that runs the collection. The stacks of the other threads are not seen: a lower bound. */
extern unsigned GC_new_kind(void **free_list, uintptr_t descriptor, int add_size, int clear);
extern void **GC_new_free_list(void);
extern void *GC_malloc_kind(size_t n, int kind);
extern int GC_get_kind_and_size(const void *p, size_t *psize);
static int nabu_raw_kind = -1, nabu_raw_atomic_kind = -1;
typedef struct { uintptr_t block; uintptr_t site; } nabu_site_entry;
#define NABU_SITES (1 << 21)
static nabu_site_entry *nabu_sites;   /* with NABU_CARD_STATS: which code allocated a block of the runtime (return address) */
static uintptr_t nabu_site_of(uintptr_t block) {
    if (nabu_sites == NULL) return 0;
    for (size_t i = (block >> 4) * 2654435761u & (NABU_SITES - 1), n = 0; n < 64; i = (i + 1) & (NABU_SITES - 1), n++) {
        if (nabu_sites[i].block == block) return nabu_sites[i].site;
        if (nabu_sites[i].block == 0) return 0;
    }
    return 0;
}
static void nabu_site_set(uintptr_t block, uintptr_t site) {
    for (size_t i = (block >> 4) * 2654435761u & (NABU_SITES - 1), n = 0; n < 64; i = (i + 1) & (NABU_SITES - 1), n++) {
        if (nabu_sites[i].block == block || nabu_sites[i].block == 0) { nabu_sites[i].block = block; nabu_sites[i].site = site; return; }
    }
}
static uintptr_t nabu_pin_holder_site;
static uint64_t nabu_pin_site_pins[64][2];   /* site, pins */   /* with NABU_CARD_STATS: the blocks of nabu_gc_alloc (runtime), told apart from the objects of compiled code (kind 1) */
static uint64_t nabu_pin_bits[1 << 20];
static uint64_t nabu_pin_shape[6][2];   /* raw blocks that pin: [first word is in the image, a heap pointer, a small number, other, ...][count, bytes] */
typedef struct { uintptr_t first; unsigned long count; size_t bytes; int kind; uintptr_t w[4]; } nabu_pin_key;
static nabu_pin_key nabu_pin_keys[4096];
static uint64_t nabu_pin_dl_entries, nabu_pin_arrays, nabu_pin_objects, nabu_pin_bytes, nabu_pin_from[6];
enum { NABU_PIN_STACK, NABU_PIN_IMAGE, NABU_PIN_TYPED, NABU_PIN_RAW, NABU_PIN_LIB, NABU_PIN_ZERO };

typedef struct { uintptr_t start; size_t size; } nabu_pin_obj;
static nabu_pin_obj *nabu_pin_objs;
static size_t nabu_pin_count, nabu_pin_cap;

static void nabu_pin_collect(void *obj, size_t bytes, void *client) {
    (void) client;
    if (nabu_pin_count == nabu_pin_cap) {
        nabu_pin_cap = nabu_pin_cap ? nabu_pin_cap * 2 : 1 << 20;
        nabu_pin_objs = (nabu_pin_obj *) realloc(nabu_pin_objs, nabu_pin_cap * sizeof *nabu_pin_objs);
    }
    nabu_pin_objs[nabu_pin_count].start = (uintptr_t) obj;
    nabu_pin_objs[nabu_pin_count].size = bytes;
    nabu_pin_count++;
}

static int nabu_pin_compare(const void *a, const void *b) {
    const uintptr_t x = ((const nabu_pin_obj *) a)->start, y = ((const nabu_pin_obj *) b)->start;
    return x < y ? -1 : x > y;
}

static void nabu_pin_word(uintptr_t w, int source) {
    if (nabu_old_base == 0 || w < nabu_old_base || w - nabu_old_base >= ((uintptr_t) 1 << 30)) return;
    /* the reachable object that holds w (interior pointers count): the last start at or below w */
    size_t lo = 0, hi = nabu_pin_count;
    while (lo < hi) {
        const size_t mid = (lo + hi) / 2;
        if (nabu_pin_objs[mid].start <= w) lo = mid + 1; else hi = mid;
    }
    if (lo == 0) return;
    const nabu_pin_obj *o = &nabu_pin_objs[lo - 1];
    if (w >= o->start + o->size) return;
    { /* a block of the runtime is allocated in the old generation to begin with: it never has to be pinned */
        size_t ignored;
        const int tk = GC_get_kind_and_size((void *) o->start, &ignored);
        if (tk == nabu_raw_kind || tk == nabu_raw_atomic_kind) return;
        const uintptr_t *tw = (const uintptr_t *) o->start;   /* an entry of the disappearing links of the collector: not program data */
        if (o->size == 32 && (tw[0] >> 56) == 0xFF && (tw[2] >> 56) == 0xFF && tw[3] == 0) return;
    }
    const uintptr_t g = (o->start - nabu_old_base) >> 4;
    if (!((nabu_old_bits[g >> 6] >> (g & 63)) & 1) || ((nabu_prev_bits[g >> 6] >> (g & 63)) & 1)) return;   /* not young */
    if ((nabu_pin_bits[g >> 6] >> (g & 63)) & 1) return;
    nabu_pin_bits[g >> 6] |= (uint64_t) 1 << (g & 63);
    nabu_pin_objects++;
    nabu_pin_bytes += o->size;
    nabu_pin_from[source]++;
    if (nabu_pin_holder_site != 0) {
        for (int i = 0; i < 64; i++) {
            if (nabu_pin_site_pins[i][0] == nabu_pin_holder_site) { nabu_pin_site_pins[i][1]++; break; }
            if (nabu_pin_site_pins[i][0] == 0) { nabu_pin_site_pins[i][0] = nabu_pin_holder_site; nabu_pin_site_pins[i][1] = 1; break; }
        }
    }
}

static void nabu_pin_visit(void *obj, size_t bytes, void *client) {
    (void) client;
    size_t kind_size;
    const int kind = GC_get_kind_and_size(obj, &kind_size);
    if (kind == 0 || kind == nabu_raw_atomic_kind) return;   /* GC_PTRFREE: strings, arrays of numbers: no pointers at all */
    const uintptr_t *words = (const uintptr_t *) obj;
    const uintptr_t first = words[0];
    /* an entry of the disappearing links of the collector itself (hidden key, next, hidden object): bookkeeping, not program data */
    if (bytes == 32 && (first >> 56) == 0xFF && (words[2] >> 56) == 0xFF && words[3] == 0) { nabu_pin_dl_entries++; return; }
    /* a free cell of the collector (not handed out yet): the first word links to the next cell, the rest is zero */
    if (nabu_old_base != 0 && first >= nabu_old_base && first - nabu_old_base < ((uintptr_t) 1 << 30)) {
        size_t nonzero = 0;
        for (size_t i = 1; i < bytes / 8; i++) nonzero += words[i] != 0;
        if (nonzero == 0) { nabu_pin_dl_entries++; return; }
    }
    size_t from = 0, to = bytes / 8;
    int source = NABU_PIN_RAW;
    const int32_t *map = NULL;
    int map_count = 0;
    if (nabu_image_size != 0 && first - nabu_image_lo < nabu_image_size && (first & 7) == 0) {
        const nabu_gc_type_info *ti = (const nabu_gc_type_info *) first;
        if ((uintptr_t) ti->name - nabu_image_lo < nabu_image_size && ti->size >= 16 && ti->size <= bytes
                && (ti->super_type == NULL || (uintptr_t) ti->super_type - nabu_image_lo < nabu_image_size)) {
            map = ti->ref_offsets;
            map_count = ti->ref_count;
            from = 2;
            to = ti->size / 8;
            source = NABU_PIN_TYPED;
        }
    }
    nabu_pin_holder_site = kind == nabu_raw_kind ? nabu_site_of((uintptr_t) obj) : 0;
    if (map == NULL && kind == nabu_raw_kind) source = NABU_PIN_LIB;   /* a block of the runtime (nabu_gc_alloc) */
    if (map == NULL && first == 0) source = NABU_PIN_ZERO;
    if (map == NULL) {
        /* [i32 length][i32 element size | class id << 8] and the elements: an array of 8-byte elements in a block that is scanned
         * is an array of references (the arrays of numbers are not scanned); an array of numbers has no pointers */
        const uint32_t length = (uint32_t) first, esize = (uint32_t) (first >> 32) & 0xff;
        const uint64_t need = (uint64_t) length * esize + 8;
        if (esize != 0 && esize <= 8 && need <= bytes && kind != nabu_raw_kind) {   /* an array: a block of compiled code with no type info (rounded up to a size class, up to 30%) */
            if (esize == 8) {   /* a reference array: every element is a precisely known reference */
                nabu_pin_arrays++;
                return;
            }
            return;   /* numbers or characters: no pointers */
        }
    }
    if (map == NULL && source == NABU_PIN_RAW) {
        const uintptr_t key = (uintptr_t) 0xFF << 56 | bytes;   /* by block size */
        size_t slot = (key >> 3) * 2654435761u % 4096;
        for (int probe = 0; probe < 4096; probe++, slot = (slot + 1) % 4096) {
            if (nabu_pin_keys[slot].count == 0) { nabu_pin_keys[slot].first = first; nabu_pin_keys[slot].count = 1; nabu_pin_keys[slot].bytes = bytes; nabu_pin_keys[slot].kind = kind; for (int q = 0; q < 4 && q < (int) (bytes / 8); q++) nabu_pin_keys[slot].w[q] = words[q]; break; }
            if (((uintptr_t) 0xFF << 56 | nabu_pin_keys[slot].bytes) == key) { nabu_pin_keys[slot].count++; break; }
        }
    }
    if (map == NULL) {
        const int shape = (nabu_image_size != 0 && first - nabu_image_lo < nabu_image_size) ? 0
                : (nabu_old_base != 0 && first >= nabu_old_base && first - nabu_old_base < ((uintptr_t) 1 << 30)) ? 1
                : first < ((uintptr_t) 1 << 32) ? 2 : 3;
        nabu_pin_shape[shape][0]++;
        nabu_pin_shape[shape][1] += bytes;
    }
    for (size_t i = from; i < to; i++) {
        if (map != NULL) {
            int mapped = 0;
            for (int k = 0; k < map_count; k++) if ((size_t) map[k] == i * 8) { mapped = 1; break; }
            if (mapped) continue;
        }
        nabu_pin_word(words[i], source);
    }
}

static void nabu_pin_scan(const void *lo, const void *hi, int source) {
    for (const uintptr_t *p = (const uintptr_t *) (((uintptr_t) lo + 7) & ~(uintptr_t) 7); (const void *) (p + 1) <= hi; p++) nabu_pin_word(*p, source);
}

static void nabu_pin_stats(unsigned long gc_no) {
    memset(nabu_pin_bits, 0, sizeof nabu_pin_bits);
    nabu_pin_objects = nabu_pin_bytes = 0;
    memset(nabu_pin_from, 0, sizeof nabu_pin_from);
    memset(nabu_pin_shape, 0, sizeof nabu_pin_shape);
    memset(nabu_pin_site_pins, 0, sizeof nabu_pin_site_pins);
    nabu_pin_holder_site = 0;
    nabu_pin_dl_entries = 0;
    memset(nabu_pin_keys, 0, sizeof nabu_pin_keys);
    volatile char here;
    nabu_pin_count = 0;
    GC_enumerate_reachable_objects_inner(nabu_pin_collect, NULL);
    qsort(nabu_pin_objs, nabu_pin_count, sizeof *nabu_pin_objs, nabu_pin_compare);
#ifdef _WIN32
    nabu_pin_scan((const void *) &here, (const void *) __readgsqword(0x08), NABU_PIN_STACK);   /* TEB.StackBase: nabu_gc_stack_base would take the lock that the collector holds */
#endif
#ifdef _WIN32
    {   /* the writable sections of the executable: statics (the only part of the image that can hold a heap pointer) */
        const char *base = (const char *) GetModuleHandle(NULL);
        const IMAGE_NT_HEADERS *nt = (const IMAGE_NT_HEADERS *) (base + ((const IMAGE_DOS_HEADER *) base)->e_lfanew);
        const IMAGE_SECTION_HEADER *sec = IMAGE_FIRST_SECTION(nt);
        for (int i = 0; i < nt->FileHeader.NumberOfSections; i++) {
            if (!(sec[i].Characteristics & IMAGE_SCN_MEM_WRITE)) continue;
            nabu_pin_scan(base + sec[i].VirtualAddress, base + sec[i].VirtualAddress + sec[i].Misc.VirtualSize, NABU_PIN_IMAGE);
        }
    }
#endif
    GC_enumerate_reachable_objects_inner(nabu_pin_visit, NULL);
    /* the 8 KB pages of the nursery that these objects would keep: distinct pages among the pinned objects */
    unsigned long pages = 0;
    {
        static uint64_t seen[1 << 14];
        memset(seen, 0, sizeof seen);
        for (size_t w = 0; w < (1 << 20); w++) {
            uint64_t bits = nabu_pin_bits[w];
            while (bits) {
                const int b = __builtin_ctzll(bits);
                bits &= bits - 1;
                const uintptr_t page = (((uintptr_t) w * 64 + (uintptr_t) b) << 4) >> 13;
                if (!((seen[(page >> 6) & ((1 << 14) - 1)] >> (page & 63)) & 1)) { seen[(page >> 6) & ((1 << 14) - 1)] |= (uint64_t) 1 << (page & 63); pages++; }
            }
        }
    }
    char line[400];
    const int n = snprintf(line, sizeof line, "[pin] collection %lu: %lu of %lu young objects pinned (%.1f%%), %lu of %lu KB (%.1f%%); from the stack %lu, the image %lu, typed words outside the map %lu, raw blocks %lu, of them blocks of the runtime (nabu_gc_alloc) %lu, blocks with a zero first word %lu; %lu pages of 8 KB (%lu KB); bookkeeping of the collector, not counted (disappearing-link entries, free cells) %lu\n",
            gc_no, (unsigned long) nabu_pin_objects, (unsigned long) nabu_young_objects,
            nabu_young_objects ? 100.0 * (double) nabu_pin_objects / (double) nabu_young_objects : 0.0,
            (unsigned long) (nabu_pin_bytes / 1024), (unsigned long) (nabu_young_bytes / 1024),
            nabu_young_bytes ? 100.0 * (double) nabu_pin_bytes / (double) nabu_young_bytes : 0.0,
            (unsigned long) nabu_pin_from[0], (unsigned long) nabu_pin_from[1], (unsigned long) nabu_pin_from[2],
            (unsigned long) nabu_pin_from[3], (unsigned long) nabu_pin_from[4], (unsigned long) nabu_pin_from[5], pages, pages * 8, (unsigned long) nabu_pin_dl_entries);
    nabu_census_write(line, n);
    const int m = snprintf(line, sizeof line, "[pin]   blocks that are no compiled object or array (all words conservative): first word in the image %lu (%lu KB), a heap pointer %lu (%lu KB), a number below 2^32 %lu (%lu KB), other %lu (%lu KB)\n",
            (unsigned long) nabu_pin_shape[0][0], (unsigned long) (nabu_pin_shape[0][1] / 1024), (unsigned long) nabu_pin_shape[1][0], (unsigned long) (nabu_pin_shape[1][1] / 1024),
            (unsigned long) nabu_pin_shape[2][0], (unsigned long) (nabu_pin_shape[2][1] / 1024), (unsigned long) nabu_pin_shape[3][0], (unsigned long) (nabu_pin_shape[3][1] / 1024));
    nabu_census_write(line, m);
    for (int rank = 0; rank < 12; rank++) {
        int best = -1;
        for (int i = 0; i < 64; i++) if (nabu_pin_site_pins[i][0] != 0 && (best < 0 || nabu_pin_site_pins[i][1] > nabu_pin_site_pins[best][1])) best = i;
        if (best < 0) break;
        const int k = snprintf(line, sizeof line, "[pin]   runtime block allocated at image+0x%lx pins %lu young objects\n", (unsigned long) (nabu_pin_site_pins[best][0] - nabu_image_lo), (unsigned long) nabu_pin_site_pins[best][1]);
        nabu_census_write(line, k);
        nabu_pin_site_pins[best][0] = 0;
    }
    for (int rank = 0; rank < 10; rank++) {
        int best = -1;
        for (int i = 0; i < 4096; i++) if (nabu_pin_keys[i].count != 0 && (best < 0 || nabu_pin_keys[i].count > nabu_pin_keys[best].count)) best = i;
        if (best < 0) break;
        const uintptr_t f = nabu_pin_keys[best].first;
        const char *what = "";
        size_t tisize = 0;
        if (nabu_image_size != 0 && f - nabu_image_lo < nabu_image_size && (f & 7) == 0) {
            const nabu_gc_type_info *ti = (const nabu_gc_type_info *) f;
            if ((uintptr_t) ti->name - nabu_image_lo < nabu_image_size) { what = ti->name; tisize = ti->size; }
        }
        const int k = snprintf(line, sizeof line, "[pin]     %6lu x first word %p (%s) in blocks of %lu bytes, type size %lu\n", nabu_pin_keys[best].count, (void *) f, what,
                (unsigned long) nabu_pin_keys[best].bytes, (unsigned long) tisize);
        nabu_census_write(line, k);
        const int k2 = snprintf(line, sizeof line, "[pin]            kind %d, words %p %p %p %p\n", nabu_pin_keys[best].kind, (void *) nabu_pin_keys[best].w[0], (void *) nabu_pin_keys[best].w[1], (void *) nabu_pin_keys[best].w[2], (void *) nabu_pin_keys[best].w[3]);
        nabu_census_write(line, k2);
        nabu_pin_keys[best].count = 0;
    }
}

static int nabu_nursery_verify_on = 0;
static void nabu_remset_verify(unsigned long gc_no);

static void nabu_gc_on_event(GC_EventType e) {
    if (e == GC_EVENT_MARK_END && nabu_nursery_verify_on) nabu_remset_verify((unsigned long) GC_get_gc_no());
    if (e == GC_EVENT_MARK_END && nabu_verify_on) nabu_verify_mark_end((unsigned long) GC_get_gc_no());
    if (e == GC_EVENT_MARK_END && nabu_card_stats_on) {
        const int had_previous = nabu_old_valid;
        nabu_card_mark_end((unsigned long) GC_get_gc_no());
        if (had_previous) nabu_pin_stats((unsigned long) GC_get_gc_no());
    }
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
    nabu_card_stats_on = getenv("NABU_CARD_STATS") != NULL;
    if (nabu_card_stats_on) {
        nabu_raw_kind = (int) GC_new_kind(GC_new_free_list(), 0, 1, 1);
        nabu_raw_atomic_kind = (int) GC_new_kind(GC_new_free_list(), 0, 0, 0);
        nabu_sites = (nabu_site_entry *) calloc(NABU_SITES, sizeof(nabu_site_entry));
    }
    nabu_verify_on = getenv("NABU_GC_VERIFY") != NULL;
    nabu_nursery_verify_on = getenv("NABU_NURSERY_VERIFY") != NULL;
    if (getenv("NABU_WB_MASK") != NULL) nabu_wb_mask = (unsigned) strtoul(getenv("NABU_WB_MASK"), NULL, 0);
    nabu_prof_path = getenv("NABU_ALLOC_PROF");
    const char *census = getenv("NABU_GC_CENSUS");
    if (census != NULL && atol(census) > 0) {
        nabu_census_at = atol(census);
        nabu_census_repeat = strchr(census, '+') != NULL;
        const char *topEnv = getenv("NABU_GC_CENSUS_TOP");
        if (topEnv != NULL && atoi(topEnv) > 0) nabu_census_top = atoi(topEnv);
    }
    if (nabu_gc_stats_on || nabu_prof_path != NULL || nabu_census_at > 0 || nabu_card_stats_on || nabu_verify_on || nabu_nursery_verify_on) GC_set_on_collection_event(nabu_gc_on_event);
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
    if (nabu_raw_kind >= 0) {
        void *block = GC_malloc_kind(n, nabu_raw_kind);
        if (nabu_sites != NULL && block != NULL) nabu_site_set((uintptr_t) block, (uintptr_t) __builtin_return_address(0));
        return block;
    }
    return GC_malloc(n);
}
void *nabu_gc_alloc_atomic(size_t n) {
    nabu_gc_alloc_bytes += n;
    if (nabu_prof_path != NULL) nabu_rprof_record(n);
    return nabu_raw_atomic_kind >= 0 ? GC_malloc_kind(n, nabu_raw_atomic_kind) : GC_malloc_atomic(n);
}
/* ---- nursery allocation front (docs/gc-generational-design.md, "Plan for the nursery build", step 1) ----
 * -Dnabu.gc.nursery=true: the compiled code allocates its objects and arrays with nabu_nursery_alloc: a bump pointer in one
 * region of NABU_NURSERY_MB megabytes (default 64) that is reserved at the start and registered with the collector as a
 * root region, one megabyte at a time as the pointer reaches it. There is no nursery collection yet: what is in the
 * region stays, and is scanned conservatively by every Boehm collection like any root. When the region is full the
 * allocation falls back to the Boehm heap. What this step shows is that nothing in the program or the runtime depends on
 * its objects being blocks of the collector. Classes that get a finalizer or a weak link (Reference, Thread,
 * FileDescriptor, and classes with finalize) are allocated in the Boehm heap by the code generator. */
#define NABU_NURSERY_CHUNK ((size_t) 1 << 20)
static char *nabu_nursery_base = NULL;
static char *nabu_nursery_limit = NULL;
static char *volatile nabu_nursery_registered = NULL;   /* end of what is a root region */
static char *volatile nabu_nursery_cur = NULL;
static pthread_mutex_t nabu_nursery_mutex = PTHREAD_MUTEX_INITIALIZER;

static void nabu_nursery_report(void) {
    const size_t used = (size_t) (nabu_nursery_cur - nabu_nursery_base);
    const size_t size = (size_t) (nabu_nursery_limit - nabu_nursery_base);
    fprintf(stderr, "[nabu/gc] nursery: %zu of %zu bytes used%s\n", used < size ? used : size, size, used > size ? " (full, the rest went to the heap)" : "");
}

/* The remembered set: the addresses of the slots (in objects of the old generation or in arrays) that were given a pointer
 * into the nursery, written by the barrier of the compiled code (nabu_remember) and by the C primitives that store
 * references. Compiled code and the runtime run under the GIL, so there is no lock. nabu_nursery_lo/span are read by the
 * barrier: a value v points into the nursery when v - lo < span (span is 0 until the first allocation). */
uint64_t nabu_nursery_lo = 0, nabu_nursery_span = 0;
unsigned nabu_wb_mask = 0xffffffffu;   /* NABU_WB_MASK: which barriers of the C primitives are on (debugging aid) */
static uintptr_t *nabu_remset = NULL;
static size_t nabu_remset_count = 0;
static int nabu_remset_overflow = 0;
#define NABU_REMSET_MAX ((size_t) 1 << 24)

static void nabu_nursery_report(void);
/* Lock-free: more than one thread can be in here (a thread that runs C code while another holds the GIL), and a growing
 * buffer that is reallocated corrupted the heap. The buffer is reserved once (untouched pages cost nothing). */
void nabu_remember(void **slot) {
    if ((uintptr_t) slot - nabu_nursery_lo < nabu_nursery_span) return;   /* a young holder: found by the collector as it copies */
    const size_t i = __atomic_fetch_add(&nabu_remset_count, 1, __ATOMIC_RELAXED);
    if (i >= NABU_REMSET_MAX) { nabu_remset_overflow = 1; return; }
    nabu_remset[i] = (uintptr_t) slot;
}

void nabu_remember_range(void *start, size_t bytes) {
    void **p = (void **) (((uintptr_t) start + 7) & ~(uintptr_t) 7);
    for (; (char *) (p + 1) <= (char *) start + bytes; p++) {
        if ((uint64_t) (uintptr_t) *p - nabu_nursery_lo < nabu_nursery_span) nabu_remember(p);
    }
}

/* NABU_NURSERY_VERIFY=1: at every mark end of the collector, every word of a reachable object of the old generation that
 * points into the used part of the nursery has to be in the remembered set. A word that is not is a store the barrier did
 * not see (a store from C, or a store with no barrier), or a number that looks like an address. Reported per holder. */
static void nabu_nursery_dryrun(unsigned long gc_no);
static uintptr_t *nabu_rs_sorted = NULL;
static size_t nabu_rs_count = 0;
static unsigned long nabu_rs_misses, nabu_rs_words, nabu_rs_objects, nabu_rs_untyped, nabu_rs_bytes_all, nabu_rs_bytes_untyped;
typedef struct { uintptr_t key; unsigned long count; const char *name; unsigned offset; size_t bytes; } nabu_rs_miss;
static nabu_rs_miss nabu_rs_table[512];

static int nabu_rs_cmp(const void *a, const void *b) {
    const uintptr_t x = *(const uintptr_t *) a, y = *(const uintptr_t *) b;
    return x < y ? -1 : x > y;
}
static int nabu_rs_has(uintptr_t slot) {
    size_t lo = 0, hi = nabu_rs_count;
    while (lo < hi) {
        const size_t mid = (lo + hi) / 2;
        if (nabu_rs_sorted[mid] < slot) lo = mid + 1; else hi = mid;
    }
    return lo < nabu_rs_count && nabu_rs_sorted[lo] == slot;
}
static void nabu_rs_visit(void *obj, size_t bytes, void *client) {
    (void) client;
    size_t ignored;
    if (GC_get_kind_and_size(obj, &ignored) == 0) return;   /* no pointers */
    nabu_rs_objects++;
    nabu_rs_bytes_all += bytes;
    const uintptr_t *words = (const uintptr_t *) obj;
    const nabu_gc_type_info *ti = NULL;
    if (nabu_image_size != 0 && words[0] - nabu_image_lo < nabu_image_size && (words[0] & 7) == 0) {
        ti = (const nabu_gc_type_info *) words[0];
        if (!((uintptr_t) ti->name - nabu_image_lo < nabu_image_size && ti->size >= 16 && ti->size <= bytes)) ti = NULL;
    }
    if (ti == NULL) { nabu_rs_untyped++; nabu_rs_bytes_untyped += bytes; }
    for (size_t i = 0; i < bytes / 8; i++) {
        const uintptr_t w = words[i];
        if (w - nabu_nursery_lo >= nabu_nursery_span) continue;
        nabu_rs_words++;
        if (nabu_rs_has((uintptr_t) &words[i])) continue;
        nabu_rs_misses++;
        if (nabu_rs_misses <= 6) {
            const uintptr_t *tgt = (const uintptr_t *) w;
            const nabu_gc_type_info *tti = (nabu_image_size != 0 && tgt[0] - nabu_image_lo < nabu_image_size) ? (const nabu_gc_type_info *) tgt[0] : NULL;
            char dbg[300];
            const int32_t aid = (int32_t) (words[0] >> 32) >> 8;
            const char *an = ti == NULL && aid > 0 && aid < 4096 ? nabu_array_type_name(aid) : NULL;
            const int dn = snprintf(dbg, sizeof dbg, "[remset] miss: holder %p (%s, %zu bytes) word %zu -> %p (%s)\n", obj,
                    ti != NULL ? ti->name : an != NULL ? an : "untyped", bytes, i, (void *) w, tti != NULL && (uintptr_t) tti->name - nabu_image_lo < nabu_image_size ? tti->name : "?");
            nabu_census_write(dbg, dn);
        }
        const uintptr_t key = ti != NULL ? (uintptr_t) ti * 1031 + i : (uintptr_t) 0xFF << 56 | bytes << 8 | (i & 0xff);
        size_t slot = (key >> 3) * 2654435761u % 512;
        for (int probe = 0; probe < 512; probe++, slot = (slot + 1) % 512) {
            if (nabu_rs_table[slot].count == 0) {
                nabu_rs_table[slot].key = key; nabu_rs_table[slot].count = 1;
                nabu_rs_table[slot].name = ti != NULL ? ti->name : NULL;
                nabu_rs_table[slot].offset = (unsigned) (i * 8); nabu_rs_table[slot].bytes = bytes;
                break;
            }
            if (nabu_rs_table[slot].key == key) { nabu_rs_table[slot].count++; break; }
        }
    }
}
static void nabu_remset_verify(unsigned long gc_no) {
    if (nabu_nursery_span == 0) return;
    nabu_rs_count = nabu_remset_count < NABU_REMSET_MAX ? nabu_remset_count : NABU_REMSET_MAX;
    nabu_rs_sorted = (uintptr_t *) malloc((nabu_rs_count + 1) * sizeof *nabu_rs_sorted);
    memcpy(nabu_rs_sorted, nabu_remset, nabu_rs_count * sizeof *nabu_rs_sorted);
    qsort(nabu_rs_sorted, nabu_rs_count, sizeof *nabu_rs_sorted, nabu_rs_cmp);
    {   /* distinct slots only, and the buffer keeps just those (the world is stopped): a slot that is stored again and again would fill it */
        size_t distinct = 0;
        for (size_t k = 0; k < nabu_rs_count; k++) {
            if (k == 0 || nabu_rs_sorted[k] != nabu_rs_sorted[k - 1]) nabu_rs_sorted[distinct++] = nabu_rs_sorted[k];
        }
        nabu_rs_count = distinct;
        memcpy(nabu_remset, nabu_rs_sorted, distinct * sizeof *nabu_rs_sorted);
        nabu_remset_count = distinct;
    }
    nabu_rs_misses = nabu_rs_words = nabu_rs_objects = nabu_rs_untyped = nabu_rs_bytes_all = nabu_rs_bytes_untyped = 0;
    memset(nabu_rs_table, 0, sizeof nabu_rs_table);
    GC_enumerate_reachable_objects_inner(nabu_rs_visit, NULL);
    char line[300];
    int n = snprintf(line, sizeof line, "[remset] collection %lu: %lu remembered slots%s, %lu objects scanned, %lu words point into the nursery, %lu of them not remembered; scanned %lu KB, of which blocks without a type %lu (%lu KB)\n",
            gc_no, (unsigned long) nabu_rs_count, nabu_remset_overflow ? " (overflow)" : "", nabu_rs_objects, nabu_rs_words, nabu_rs_misses,
            nabu_rs_bytes_all / 1024, nabu_rs_untyped, nabu_rs_bytes_untyped / 1024);
    nabu_census_write(line, n);
    for (int rank = 0; rank < 12; rank++) {
        int best = -1;
        for (int i = 0; i < 512; i++) if (nabu_rs_table[i].count != 0 && (best < 0 || nabu_rs_table[i].count > nabu_rs_table[best].count)) best = i;
        if (best < 0) break;
        if (nabu_rs_table[best].name != NULL) n = snprintf(line, sizeof line, "[remset]   %8lu x  %s +%u\n", nabu_rs_table[best].count, nabu_rs_table[best].name, nabu_rs_table[best].offset);
        else n = snprintf(line, sizeof line, "[remset]   %8lu x  untyped block of %zu bytes, word %u\n", nabu_rs_table[best].count, nabu_rs_table[best].bytes, nabu_rs_table[best].offset / 8);
        nabu_census_write(line, n);
        nabu_rs_table[best].count = 0;
    }
    free(nabu_rs_sorted);
    nabu_rs_sorted = NULL;
    nabu_nursery_dryrun(gc_no);
}

/* Dry run of a nursery collection (NABU_NURSERY_VERIFY=1, nothing is moved or freed): walk the used nursery object by object
 * (type info or array header give the extent), mark what the remembered set, the stack of this thread and the writable image
 * sections reach, trace through the nursery, and report live bytes against used bytes. It answers: does the layout allow
 * walking, and how much of the nursery would a collection reclaim. Other threads' stacks are not scanned, so "live" is a
 * lower bound; remembered slots of dead old holders count as roots, which makes it an upper bound: the two bracket it. */
static uint64_t *nabu_dr_start = NULL, *nabu_dr_mark = NULL;
static uintptr_t *nabu_dr_stack = NULL;
static size_t nabu_dr_sp = 0, nabu_dr_cap = 0;
static unsigned long nabu_dr_live_objs, nabu_dr_live_bytes;

static size_t nabu_dr_extent(const uintptr_t *w, size_t room, const nabu_gc_type_info **tip) {
    *tip = NULL;
    const uintptr_t first = w[0];
    if (nabu_image_size != 0 && first - nabu_image_lo < nabu_image_size && (first & 7) == 0) {
        const nabu_gc_type_info *ti = (const nabu_gc_type_info *) first;
        if ((uintptr_t) ti->name - nabu_image_lo < nabu_image_size && ti->size >= 16 && ti->size <= room) {
            *tip = ti;
            return (ti->size + 15) & ~(size_t) 15;
        }
        return 0;
    }
    const uint32_t length = (uint32_t) first, esize = (uint32_t) (first >> 32) & 0xff;
    if (esize == 1 || esize == 2 || esize == 4 || esize == 8) {
        const size_t bytes = ((size_t) length * esize + 8 + 15) & ~(size_t) 15;
        if (bytes <= room) return bytes;
    }
    return 0;
}

/* the start of the object that holds w (interior pointers count), or 0 */
static uintptr_t nabu_dr_find(uintptr_t w) {
    if (w - nabu_nursery_lo >= nabu_nursery_span) return 0;
    size_t g = (w - nabu_nursery_lo) >> 4;
    for (size_t steps = 0; steps < 1000000; steps++) {   /* back to the nearest start */
        if ((nabu_dr_start[g >> 6] >> (g & 63)) & 1) return nabu_nursery_lo + (g << 4);
        if (g == 0) return 0;
        g--;
    }
    return 0;
}

static void nabu_dr_push(uintptr_t w) {
    const uintptr_t o = nabu_dr_find(w);
    if (o == 0) return;
    const size_t g = (o - nabu_nursery_lo) >> 4;
    if ((nabu_dr_mark[g >> 6] >> (g & 63)) & 1) return;
    nabu_dr_mark[g >> 6] |= (uint64_t) 1 << (g & 63);
    if (nabu_dr_sp == nabu_dr_cap) {
        nabu_dr_cap = nabu_dr_cap ? nabu_dr_cap * 2 : 65536;
        nabu_dr_stack = (uintptr_t *) realloc(nabu_dr_stack, nabu_dr_cap * sizeof *nabu_dr_stack);
    }
    nabu_dr_stack[nabu_dr_sp++] = o;
}

static void nabu_nursery_dryrun(unsigned long gc_no) {
    if (nabu_nursery_span == 0 || nabu_nursery_cur <= nabu_nursery_base) return;
    const char *top = nabu_nursery_cur < nabu_nursery_limit ? nabu_nursery_cur : nabu_nursery_limit;   /* a full region keeps counting up */
    const size_t used = (size_t) (top - nabu_nursery_base);
    const size_t granules = (used >> 4) + 1, words64 = (granules >> 6) + 1;
    nabu_dr_start = (uint64_t *) calloc(words64, 8);
    nabu_dr_mark = (uint64_t *) calloc(words64, 8);
    nabu_dr_sp = 0;
    unsigned long objects = 0, bad = 0, bad_at = 0, zero_gaps = 0;
    size_t off = 0;
    while (off < used) {
        const uintptr_t *w = (const uintptr_t *) (nabu_nursery_base + off);
        if (w[0] == 0 && w[1] == 0) { off += 16; zero_gaps++; continue; }   /* a hole: an allocation in flight or padding */
        const nabu_gc_type_info *ti;
        const size_t ext = nabu_dr_extent(w, used - off, &ti);
        if (ext == 0) {
            if (bad++ == 0) bad_at = off;
            off += 16;   /* resynchronize */
            continue;
        }
        const size_t g = off >> 4;
        nabu_dr_start[g >> 6] |= (uint64_t) 1 << (g & 63);
        objects++;
        off += ext;
    }
    nabu_dr_live_objs = nabu_dr_live_bytes = 0;
    for (size_t i = 0; i < nabu_remset_count && i < NABU_REMSET_MAX; i++) nabu_dr_push(*(const uintptr_t *) nabu_remset[i]);
    const unsigned long from_remset = 0;
    (void) from_remset;
#ifdef _WIN32
    {
        volatile char here;
        for (const uintptr_t *p = (const uintptr_t *) (((uintptr_t) &here + 7) & ~(uintptr_t) 7); (const void *) (p + 1) <= (const void *) __readgsqword(0x08); p++) nabu_dr_push(*p);
        const char *base = (const char *) GetModuleHandle(NULL);
        const IMAGE_NT_HEADERS *nt = (const IMAGE_NT_HEADERS *) (base + ((const IMAGE_DOS_HEADER *) base)->e_lfanew);
        const IMAGE_SECTION_HEADER *sec = IMAGE_FIRST_SECTION(nt);
        for (int i = 0; i < nt->FileHeader.NumberOfSections; i++) {
            if (!(sec[i].Characteristics & IMAGE_SCN_MEM_WRITE)) continue;
            const uintptr_t *lo = (const uintptr_t *) (base + sec[i].VirtualAddress);
            for (const uintptr_t *p = lo; (const void *) (p + 1) <= (const void *) (base + sec[i].VirtualAddress + sec[i].Misc.VirtualSize); p++) nabu_dr_push(*p);
        }
    }
#endif
    while (nabu_dr_sp > 0) {
        const uintptr_t o = nabu_dr_stack[--nabu_dr_sp];
        const uintptr_t *w = (const uintptr_t *) o;
        const nabu_gc_type_info *ti;
        const size_t ext = nabu_dr_extent(w, (size_t) (nabu_nursery_limit - (char *) o), &ti);
        nabu_dr_live_objs++;
        nabu_dr_live_bytes += ext;
        if (ti != NULL) {
            for (int k = 0; k < ti->ref_count; k++) nabu_dr_push(*(const uintptr_t *) (o + (size_t) ti->ref_offsets[k]));
        } else if (((w[0] >> 32) & 0xff) == 8) {
            const uint32_t length = (uint32_t) w[0];
            for (uint32_t k = 0; k < length; k++) nabu_dr_push(w[1 + k]);
        }
    }
    char line[300];
    const int n = snprintf(line, sizeof line, "[nursery] collection %lu dry run: used %zu KB, %lu objects walked (%lu bad headers, first at +%lu, %lu zero gaps), live %lu objects %lu KB (%.1f%% of used)\n",
            gc_no, used / 1024, objects, bad, bad_at, zero_gaps, nabu_dr_live_objs, nabu_dr_live_bytes / 1024, 100.0 * (double) nabu_dr_live_bytes / (double) used);
    nabu_census_write(line, n);
    free(nabu_dr_start); free(nabu_dr_mark);
    nabu_dr_start = nabu_dr_mark = NULL;
}

static void nabu_nursery_init(void) {
    const char *mb = getenv("NABU_NURSERY_MB");
    size_t size = ((size_t) (mb != NULL && atol(mb) > 0 ? atol(mb) : 64)) << 20;
#ifdef _WIN32
    void *p = VirtualAlloc(NULL, size, MEM_RESERVE | MEM_COMMIT, PAGE_READWRITE);
#else
    void *p = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (p == MAP_FAILED) p = NULL;
#endif
    if (p == NULL) { fprintf(stderr, "[nabu/gc] nursery of %zu bytes could not be reserved\n", size); return; }
    nabu_nursery_base = (char *) p;
    nabu_nursery_limit = nabu_nursery_base + size;
    nabu_nursery_registered = nabu_nursery_base;
    nabu_nursery_cur = nabu_nursery_base;
#ifdef _WIN32
    nabu_remset = (uintptr_t *) VirtualAlloc(NULL, NABU_REMSET_MAX * sizeof(uintptr_t), MEM_RESERVE | MEM_COMMIT, PAGE_READWRITE);
#else
    nabu_remset = (uintptr_t *) mmap(NULL, NABU_REMSET_MAX * sizeof(uintptr_t), PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (nabu_remset == (uintptr_t *) MAP_FAILED) nabu_remset = NULL;
#endif
    if (nabu_remset == NULL) { fprintf(stderr, "[nabu/gc] remembered set could not be reserved\n"); abort(); }
    nabu_nursery_lo = (uint64_t) (uintptr_t) nabu_nursery_base;
    nabu_nursery_span = size;
    if (getenv("NABU_GC_ALLOC_STATS") != NULL) {
        atexit(nabu_nursery_report);
        fprintf(stderr, "[nabu/gc] nursery of %zu bytes reserved\n", size);
    }
}

int nabu_in_nursery(const void *p) {
    return (const char *) p >= nabu_nursery_base && (const char *) p < nabu_nursery_limit;
}

static void *nabu_nursery_slow(char *start, size_t n) {
    pthread_mutex_lock(&nabu_nursery_mutex);
    while (nabu_nursery_registered < start + n && nabu_nursery_registered < nabu_nursery_limit) {
        GC_add_roots(nabu_nursery_registered, nabu_nursery_registered + NABU_NURSERY_CHUNK);
        nabu_nursery_registered += NABU_NURSERY_CHUNK;
    }
    pthread_mutex_unlock(&nabu_nursery_mutex);
    return start + n <= nabu_nursery_registered ? start : NULL;
}

void *nabu_nursery_alloc(size_t n) {
    if (nabu_nursery_base == NULL) {
        pthread_mutex_lock(&nabu_nursery_mutex);
        if (nabu_nursery_base == NULL) nabu_nursery_init();
        if (nabu_nursery_base == NULL) nabu_nursery_base = nabu_nursery_limit = (char *) 1;   /* none: always the heap */
        pthread_mutex_unlock(&nabu_nursery_mutex);
    }
    n = (n + 15) & ~(size_t) 15;
    char *start = __atomic_fetch_add(&nabu_nursery_cur, n, __ATOMIC_RELAXED);
    if (start >= nabu_nursery_base && start + n <= nabu_nursery_limit) {
        if (start + n <= nabu_nursery_registered) return start;
        void *r = nabu_nursery_slow(start, n);
        if (r != NULL) return r;
    }
    return GC_malloc(n);
}
void *nabu_nursery_alloc_atomic(size_t n) { return nabu_nursery_alloc(n); }

void *nabu_gc_alloc_pinned(size_t n) { return GC_malloc_uncollectable(n); }
void *nabu_gc_realloc(void *p, size_t n) { return GC_realloc(p, n); }
void nabu_gc_free(void *p) { GC_free(p); }
void nabu_gc_discard(void *p) { (void) p; /* the wrapper of an exception and the like are reclaimed by the collector */ }

void nabu_gc_collect(void) {
    GC_gcollect();
    GC_invoke_finalizers();
}

void nabu_gc_set_finalizer(void *obj, void (*fn)(void *obj, void *data)) {
    if (nabu_in_nursery(obj)) {
        fprintf(stderr, "[nabu/gc] finalizer for an object in the nursery (%p): its class has to be allocated in the heap\n", obj);
        abort();
    }
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
/* The card table of the write barrier experiment (-Dnabu.gc.cardMarking=true, docs/gc-generational-design.md): one byte
 * per 512 bytes of address space, set by every store of a reference. NABU_CARD_STATS=1 prints how many were set at each
 * collection (and clears them), which is the share of the heap that a nursery collection would have to scan. */
unsigned char nabu_card_table[1 << 24];

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

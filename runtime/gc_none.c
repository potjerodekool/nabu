/*
 * No collector: memory is taken from the C heap and not collected (the programs that run briefly, tests, and a baseline
 * for the others). Compiled with -DNABU_GC_NONE. The compiled code calls nabu_none_alloc and nabu_none_alloc_atomic.
 *
 * Every block is its own malloc block, so nabu_gc_free and nabu_gc_discard can give memory back: the runtime does that for
 * what it knows to be finished (a wrapper of an exception that was handled).
 */
#ifndef NABU_GC_NONE
#error "gc_none.c needs -DNABU_GC_NONE"
#endif

#ifdef _WIN32
#include <windows.h>
#else
#include <pthread.h>
#endif
#include <stdio.h>
#include <stdlib.h>

#include "nabu_gc.h"

static void *nabu_none_check(void *p) {
    if (p == NULL) {
        fprintf(stderr, "nabu: out of memory (no garbage collector: nothing is ever freed)\n");
        abort();
    }
    return p;
}

/* What the compiled code calls: zeroed memory, and memory that the code clears itself. */
static volatile size_t nabu_gc_alloc_bytes = 0;
static void nabu_gc_alloc_stats_report(void) {
    if (getenv("NABU_GC_ALLOC_STATS") != NULL) {
        fprintf(stderr, "[nabu/gc] total allocated bytes: %zu\n", nabu_gc_alloc_bytes);
    }
}
__attribute__((constructor)) static void nabu_gc_alloc_stats_init(void) {
    atexit(nabu_gc_alloc_stats_report);
}

void *nabu_none_alloc(size_t n) { nabu_gc_alloc_bytes += n; return nabu_none_check(calloc(1, n)); }
void *nabu_none_alloc_atomic(size_t n) { nabu_gc_alloc_bytes += n; return nabu_none_check(malloc(n)); }

void *nabu_gc_alloc(size_t n) { return nabu_none_alloc(n); }
void *nabu_gc_alloc_atomic(size_t n) { return nabu_none_alloc_atomic(n); }
void *nabu_gc_alloc_pinned(size_t n) { return nabu_none_alloc(n); }
void *nabu_gc_realloc(void *p, size_t n) { return nabu_none_check(realloc(p, n)); }
void nabu_gc_free(void *p) { free(p); }
void nabu_gc_discard(void *p) { free(p); }

void nabu_gc_init(void) { }
void nabu_gc_collect(void) { }
void nabu_gc_set_finalizer(void *obj, void (*fn)(void *obj, void *data)) { (void) obj; (void) fn; }
void nabu_gc_pause(void) { }
void nabu_gc_resume(void) { }

void nabu_gc_threads_enabled(void) { }

#ifdef _WIN32
typedef struct { void *(*start)(void *); void *arg; } nabu_none_thread;

static DWORD WINAPI nabu_none_thread_main(LPVOID parameter) {
    nabu_none_thread t = *(nabu_none_thread *) parameter;
    free(parameter);
    t.start(t.arg);
    return 0;
}

/* CreateThread with a reservation: a big stack costs nothing until it is used (pthread_create commits all of it). */
int nabu_gc_thread_create(void *(*start)(void *), void *arg, size_t stack_size) {
    nabu_none_thread *t = (nabu_none_thread *) malloc(sizeof *t);
    if (t == NULL) return -1;
    t->start = start;
    t->arg = arg;
    HANDLE created = CreateThread(NULL, stack_size, nabu_none_thread_main, t, STACK_SIZE_PARAM_IS_A_RESERVATION, NULL);
    if (created == NULL) { free(t); return -1; }
    CloseHandle(created);
    return 0;
}
#else
int nabu_gc_thread_create(void *(*start)(void *), void *arg, size_t stack_size) {
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
    pthread_attr_setstacksize(&attr, stack_size);
    pthread_t handle;
    const int rc = pthread_create(&handle, &attr, start, arg);
    pthread_attr_destroy(&attr);
    return rc;
}
#endif

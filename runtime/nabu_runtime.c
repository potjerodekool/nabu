/**
 * Nabu Runtime Helpers — implementatie.
 *
 * Biedt de basisruntime-functionaliteit voor door de Nabu compiler
 * gegenereerde LLVM IR.
 *
 * Compilatie:
 *   gcc -shared -fPIC -o libnabu_runtime.so nabu_runtime.c -lpthread   (Linux)
 *   gcc -shared -o nabu_runtime.dll nabu_runtime.c -lpthread            (Windows/MinGW)
 *   clang -shared -fPIC -o libnabu_runtime.dylib nabu_runtime.c         (macOS)
 */

#include "nabu_runtime.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifdef _WIN32
#include <windows.h>
#else
#include <pthread.h>
#include <stdatomic.h>
#endif

/* -------------------------------------------------------
 * Itanium ABI exception throwing (Linux / macOS / MinGW)
 * ------------------------------------------------------- */

#if defined(__GNUC__) || defined(__clang__)

#include <unwind.h>

/**
 * Unwind exception wrapper — bevat de Itanium ABI _Unwind_Exception
 * als eerste lid (voor pointer-compatibiliteit) plus de Nabu object pointer.
 *
 * Wanneer nabu_throw wordt aangeroepen, wordt een nabu_unwind_exception
 * gealloceerd, de Nabu object pointer erin opgeslagen, en
 * _Unwind_RaiseException aangeroepen. De unwinder zoekt dan het
 * dichtstbijzijnde catch-punt (landing pad) en geeft de pointer naar
 * het nabu_unwind_exception door aan de landing pad.
 *
 * De landing pad kan dan nabu_catch() aanroepen om de Nabu object
 * pointer uit de wrapper te extraheren.
 */
typedef struct nabu_unwind_exception {
    _Unwind_Exception unwind_header;
    void *nabu_object;
} nabu_unwind_exception;

/**
 * Unwind exception class identifier (8 bytes).
 * Gebruikt om Nabu exceptions te onderscheiden van C++ exceptions.
 */
static const uint64_t NABU_EXC_CLASS = 0x4e4142554e414255ULL; /* "NABUNABU" */

/**
 * Cleanup callback — wordt door de unwinder aangeroepen wanneer de
 * exception niet meer nodig is (bijv. na een catch of wanneer geen
 * handler gevonden is).
 */
static void nabu_exception_cleanup(_Unwind_Reason_Code reason,
                                   _Unwind_Exception *exc) {
    (void)reason;
    nabu_unwind_exception *nabu_exc = (nabu_unwind_exception *)exc;
    free(nabu_exc);
}

void nabu_throw(void *exception) {
    nabu_unwind_exception *exc = malloc(sizeof(nabu_unwind_exception));
    if (exc == NULL) {
        fprintf(stderr, "nabu: kan geen geheugen alloceren voor exception\n");
        abort();
    }

    memset(&exc->unwind_header, 0, sizeof(_Unwind_Exception));
    exc->unwind_header.exception_class = NABU_EXC_CLASS;
    exc->unwind_header.exception_cleanup = nabu_exception_cleanup;
    exc->nabu_object = exception;

    _Unwind_Reason_Code result = _Unwind_RaiseException(&exc->unwind_header);

    /* Als we hier komen is er geen handler gevonden */
    (void)result;
    fprintf(stderr, "nabu: onbehandelde exception\n");
    abort();
}

void *nabu_catch(void *unwind_exc) {
    if (unwind_exc == NULL) {
        return NULL;
    }
    nabu_unwind_exception *exc = (nabu_unwind_exception *)unwind_exc;
    return exc->nabu_object;
}

#else
/* -------------------------------------------------------
 * Windows SEH fallback (MSVC)
 * ------------------------------------------------------- */

/**
 * Windows SEH implementatie.
 *
 * Op Windows met MSVC worden exceptions via SEH (Structured Exception
 * Handling) afgehandeld. Voor LLVM IR dat usemaakt van
 * __gcc_personality_v0 (Itanium ABI) hebben we een compatibele
 * implementatie nodig.
 *
 * Voorlopig slaan we de exception op en aborteren we. Volledige SEH
 * integratie vereist het gebruik van RtlUnwindEx of een vergelijkbare
 * Windows API.
 */
static __thread void *tls_last_exception = NULL;

void nabu_throw(void *exception) {
    tls_last_exception = exception;
    fprintf(stderr, "nabu: onbehandelde exception (SEH niet geimplementeerd)\n");
    abort();
}

void *nabu_catch(void *unwind_exc) {
    /* Op Windows retourneren we de TLS exception als fallback */
    (void)unwind_exc;
    void *ex = tls_last_exception;
    tls_last_exception = NULL;
    return ex;
}

#endif

/* -------------------------------------------------------
 * InstanceOf
 * ------------------------------------------------------- */

int nabu_instanceof(void *obj, const char *type_name) {
    if (obj == NULL) {
        return 0;
    }

    nabu_object *header = (nabu_object *)obj;
    nabu_type_info *current = header->type;

    while (current != NULL) {
        if (strcmp(current->name, type_name) == 0) {
            return 1;
        }
        current = current->super_type;
    }

    return 0;
}

/* -------------------------------------------------------
 * Monitor (synchronized)
 * ------------------------------------------------------- */

#ifdef _WIN32

/* Windows: CRITICAL_SECTION per object (lazy geïnitialiseerd) */
static CRITICAL_SECTION monitor_cs;
static int monitor_cs_init = 0;

static void ensure_monitor_init(void) {
    if (!monitor_cs_init) {
        InitializeCriticalSection(&monitor_cs);
        monitor_cs_init = 1;
    }
}

void nabu_monitorenter(void *obj) {
    if (obj == NULL) {
        fprintf(stderr, "nabu: synchronized op null object\n");
        abort();
    }
    ensure_monitor_init();
    nabu_object *header = (nabu_object *)obj;

    /* Eenvoudige spinlock via de monitor_count */
    EnterCriticalSection(&monitor_cs);
    while (header->monitor_count > 0 && header->thread_id != (int)GetCurrentThreadId()) {
        LeaveCriticalSection(&monitor_cs);
        /* busy wait — in productie: use proper condition variable */
        EnterCriticalSection(&monitor_cs);
    }
    header->monitor_count++;
    header->thread_id = (int)GetCurrentThreadId();
    LeaveCriticalSection(&monitor_cs);
}

void nabu_monitorexit(void *obj) {
    if (obj == NULL) {
        fprintf(stderr, "nabu: synchronized op null object\n");
        abort();
    }
    ensure_monitor_init();
    nabu_object *header = (nabu_object *)obj;

    EnterCriticalSection(&monitor_cs);
    header->monitor_count--;
    if (header->monitor_count == 0) {
        header->thread_id = 0;
    }
    LeaveCriticalSection(&monitor_cs);
}

#else

/* POSIX: pthread_mutex_t per object (lazy geïnitialiseerd via spinlock) */
static pthread_mutex_t global_monitor_mutex = PTHREAD_MUTEX_INITIALIZER;

void nabu_monitorenter(void *obj) {
    if (obj == NULL) {
        fprintf(stderr, "nabu: synchronized op null object\n");
        abort();
    }

    nabu_object *header = (nabu_object *)obj;

    pthread_mutex_lock(&global_monitor_mutex);
    while (header->monitor_count > 0 && header->thread_id != (int)pthread_self()) {
        pthread_mutex_unlock(&global_monitor_mutex);
        /* busy wait — in productie: gebruik condition variables */
        pthread_mutex_lock(&global_monitor_mutex);
    }
    header->monitor_count++;
    header->thread_id = (int)pthread_self();
    pthread_mutex_unlock(&global_monitor_mutex);
}

void nabu_monitorexit(void *obj) {
    if (obj == NULL) {
        fprintf(stderr, "nabu: synchronized op null object\n");
        abort();
    }

    nabu_object *header = (nabu_object *)obj;

    pthread_mutex_lock(&global_monitor_mutex);
    header->monitor_count--;
    if (header->monitor_count == 0) {
        header->thread_id = 0;
    }
    pthread_mutex_unlock(&global_monitor_mutex);
}

#endif

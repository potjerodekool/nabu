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
#include <stdint.h>

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
    struct _Unwind_Exception unwind_header;
    void *nabu_object;
} nabu_unwind_exception;

/**
 * Unwind exception class identifier (8 bytes).
 * Gebruikt om Nabu exceptions te onderscheiden van C++ exceptions.
 */
static const uint64_t NABU_EXC_CLASS = 0x4e4142554e414255ULL; /* "NABUNABU" */

/* -------------------------------------------------------
 * Library-object allocatie (via type-naam; voor klassen waarvan de
 * backend geen layout kent, bv. java/lang/Exception).
 * ------------------------------------------------------- */

/* Cache voor runtime-gegenereerde type-info; max 64 typer namen per
   proces (voldoende voor dev/demo). Namen verwijzen naar constante
   backend-string-globals, dus de pointers blijven geldig. */
#define NABU_MAX_TYPES 64

static nabu_type_info *nabu_type_info_for(const char *name) {
    static nabu_type_info cache[NABU_MAX_TYPES];
    static int count = 0;
    for (int i = 0; i < count; i++) {
        if (strcmp(cache[i].name, name) == 0)
            return &cache[i];
    }
    if (count >= NABU_MAX_TYPES) {
        fprintf(stderr, "nabu: te veel runtime-type-info entries\n");
        abort();
    }
    nabu_type_info *ti = &cache[count++];
    ti->name = name;
    ti->size = sizeof(nabu_object);
    ti->super_type = NULL;   /* exacte naam-match via nabu_instanceof volstaat */
    ti->field_count = 0;
    return ti;
}

/**
 * Alloceert een object van een onbekende (library)klasse met een geldig
 * nabu_object-header zodat nabu_instanceof/nabu_can_catch erop werken.
 * De type-info wordt per interne naam gecachet.
 */
void *nabu_new_object(const char *type_name, size_t size) {
    size = size < sizeof(nabu_object) ? sizeof(nabu_object) : size;
    nabu_object *obj = (nabu_object *)malloc(size);
    if (obj == NULL) {
        fprintf(stderr, "nabu: kan niet alloceren voor %s\n", type_name);
        abort();
    }
    memset(obj, 0, size);
    obj->type = nabu_type_info_for(type_name);
    return obj;
}

/**
 * Constructor van java.lang.Exception (en objecten met lege layout).
 * De runtime voorziet de definitie zodat het externe symbool resolvet.
 */
void java_lang_Exception_init(void *self) {
    (void)self; /* geen instantievelden in de minimale layout */
}

/**
 * Cleanup callback — wordt door de unwinder aangeroepen wanneer de
 * exception niet meer nodig is (bijv. na een catch of wanneer geen
 * handler gevonden is).
 */
static void nabu_exception_cleanup(_Unwind_Reason_Code reason,
                                   struct _Unwind_Exception *exc) {
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

    memset(&exc->unwind_header, 0, sizeof(struct _Unwind_Exception));
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

int nabu_can_catch(void *obj, const char *type_name) {
    return nabu_instanceof(obj, type_name);
}

/* -------------------------------------------------------
 * Nabu-eigen SEH / Itanium persoonlijkheid
 * -------------------------------------------------------
 * Op Windows/x86_64-MinGW (SEH) bestaat __gcc_personality_v0 NIET; de
 * enige unwinder is libgcc_s_seh-1.dll, die functies als
 * _GCC_specific_handler en _Unwind_* exporteert.
 *
 * Een .xdata-EHANDLER wordt met de rauwe 4-arg SEH-signatuur
 * (PEXCEPTION_RECORD, void*, PCONTEXT, PDISPATCHER_CONTEXT) aangeroepen.
 * GCC's eigen persoonlijkheden zijn 4-arg wrappers die doorsturen naar
 * _GCC_specific_handler(ms_exc, frame, ctx, disp, echte_itanium_personality).
 *
 * Hier doen we precies hetzelfde: nabu_seh_personality is de 4-arg wrapper
 * die in .xdata staat en doorstuurt naar nabu_itanium_personality. Die leest
 * de LSDA (.gcc_except_table) die LLVM in .xdata plaatst (=$disp->HandlerData),
 * zoekt de call-site voor de gooiende IP en installeert het landingspad met
 * RAX=nabu_unwind_exception* en RDX=selector. De echte type-filter gebeurt in
 * code via nabu_can_catch.
 */

#ifdef _WIN32
#include <windows.h>
#endif

/* leb128-decoders (excerpt uit GCC unwind-pe.h). */
static const unsigned char *
nabu_read_uleb128(const unsigned char *p, _uleb128_t *val) {
    _uleb128_t v = 0;
    unsigned int shift = 0;
    unsigned char b;
    do {
        b = *p++;
        v |= (_uleb128_t)(b & 0x7f) << shift;
        shift += 7;
    } while (b & 0x80);
    *val = v;
    return p;
}

static const unsigned char *
nabu_read_sleb128(const unsigned char *p, _sleb128_t *val) {
    _sleb128_t v = 0;
    unsigned int shift = 0;
    unsigned char b;
    do {
        b = *p++;
        v |= (_sleb128_t)(b & 0x7f) << shift;
        shift += 7;
    } while (b & 0x80);
    if (shift < (8 * sizeof(v)) && (b & 0x40))
        v |= -(_sleb128_t)1 << shift;
    *val = v;
    return p;
}

/* Beperkte DW_EH_PE-ondersteuning (wat LLVM/SEH daadwerkelijk emitteert). */
#define NABU_DW_EH_PE_absptr     0x00
#define NABU_DW_EH_PE_omit       0xff
#define NABU_DW_EH_PE_uleb128    0x01
#define NABU_DW_EH_PE_sleb128    0x09
#define NABU_DW_EH_PE_udata2     0x02
#define NABU_DW_EH_PE_udata4     0x03
#define NABU_DW_EH_PE_udata8     0x04
#define NABU_DW_EH_PE_sdata2     0x0a
#define NABU_DW_EH_PE_sdata4     0x0b
#define NABU_DW_EH_PE_sdata8     0x0c

static const unsigned char *
nabu_read_encoded_value(struct _Unwind_Context *context,
                        unsigned char encoding,
                        const unsigned char *p,
                        _Unwind_Ptr *val) {
    union { _Unwind_Ptr result; unsigned short s2; unsigned int s4;
            unsigned long long s8; unsigned char bytes[8]; } u;
    (void)context;

    switch (encoding & 0x0f) {
    case NABU_DW_EH_PE_absptr:
        switch (encoding & 0x70) {
        case NABU_DW_EH_PE_udata2: u.s2 = (unsigned short)(p[0] | (p[1] << 8)); p += 2; break;
        case NABU_DW_EH_PE_udata4: u.s4 = (unsigned int)p[0] | ((unsigned int)p[1] << 8)
                                    | ((unsigned int)p[2] << 16) | ((unsigned int)p[3] << 24); p += 4; break;
        case NABU_DW_EH_PE_udata8: for (int i = 0; i < 8; ++i) u.bytes[i] = p[i]; p += 8; break;
        default: u.result = (unsigned int)p[0] | ((unsigned int)p[1] << 8)
                            | ((unsigned int)p[2] << 16) | ((unsigned int)p[3] << 24); p += 4; break;
        }
        break;
    case NABU_DW_EH_PE_uleb128: { _uleb128_t tmp; p = nabu_read_uleb128(p, &tmp); u.result = (_Unwind_Ptr)tmp; break; }
    case NABU_DW_EH_PE_sleb128: { _sleb128_t tmp; p = nabu_read_sleb128(p, &tmp); u.result = (_Unwind_Ptr)tmp; break; }
    case NABU_DW_EH_PE_udata2:  u.s2 = (unsigned short)(p[0] | (p[1] << 8)); p += 2; break;
    case NABU_DW_EH_PE_udata4:  u.s4 = (unsigned int)p[0] | ((unsigned int)p[1] << 8)
                                    | ((unsigned int)p[2] << 16) | ((unsigned int)p[3] << 24); p += 4; break;
    case NABU_DW_EH_PE_udata8:  for (int i = 0; i < 8; ++i) u.bytes[i] = p[i]; p += 8; break;
    default: return 0;
    }
    *val = u.result;
    return p;
}

typedef struct {
    _Unwind_Ptr Start;
    _Unwind_Ptr LPStart;
    unsigned char ttype_encoding;
    unsigned char call_site_encoding;
    const unsigned char *TType;
    const unsigned char *action_table;
} nabu_lsda_info;

static const unsigned char *
nabu_parse_lsda_header(struct _Unwind_Context *context,
                       const unsigned char *p,
                       nabu_lsda_info *info) {
    unsigned char lpstart_encoding;
    _uleb128_t tmp;

    info->Start = context ? _Unwind_GetRegionStart(context) : 0;

    lpstart_encoding = *p++;
    if (lpstart_encoding != NABU_DW_EH_PE_omit)
        p = nabu_read_encoded_value(context, lpstart_encoding, p, &info->LPStart);
    else
        info->LPStart = info->Start;

    info->ttype_encoding = *p++;
    if (info->ttype_encoding != NABU_DW_EH_PE_omit) {
        p = nabu_read_uleb128(p, &tmp);
        info->TType = p + tmp;
    } else
        info->TType = 0;

    info->call_site_encoding = *p++;
    p = nabu_read_uleb128(p, &tmp);
    info->action_table = p + tmp;
    return p;
}

/* Itanium-persoonlijkheid; door _GCC_specific_handler aangeroepen (5-arg). */
static _Unwind_Reason_Code
nabu_itanium_personality(int version,
                         _Unwind_Action actions,
                         _Unwind_Exception_Class exception_class,
                         struct _Unwind_Exception *ue_header,
                         struct _Unwind_Context *context) {
    nabu_lsda_info info;
    const unsigned char *lsda, *p;
    _Unwind_Ptr landing_pad, ip;
    int ip_before_insn = 0;
    int selector = 0;

    if (version != 1)
        return _URC_FATAL_PHASE1_ERROR;
    (void)exception_class;

    lsda = (const unsigned char *)_Unwind_GetLanguageSpecificData(context);
    if (!lsda)
        return _URC_CONTINUE_UNWIND;

    p = nabu_parse_lsda_header(context, lsda, &info);

    ip = _Unwind_GetIPInfo(context, &ip_before_insn);
    if (!ip_before_insn)
        --ip;

    landing_pad = 0;

    /* Doorzoek de call-site tabel. */
    while (p < info.action_table) {
        _Unwind_Ptr cs_start, cs_len, cs_lp;
        _uleb128_t cs_action;

        p = nabu_read_encoded_value(context, info.call_site_encoding, p, &cs_start);
        p = nabu_read_encoded_value(context, info.call_site_encoding, p, &cs_len);
        p = nabu_read_encoded_value(context, info.call_site_encoding, p, &cs_lp);
        p = nabu_read_uleb128(p, &cs_action);

        if (ip < info.Start + cs_start) {
            p = info.action_table;   /* gesorteerd: voorbij */
            break;
        }
        if (ip < info.Start + cs_start + cs_len) {
            if (cs_lp) {
                landing_pad = info.LPStart + cs_lp;
                /* Selector = 1 bij een actie-record; de echte type-filter
                   gebeurt in code via nabu_can_catch. */
                selector = cs_action ? 1 : 0;
            }
            break;
        }
    }

    if (landing_pad == 0)
        return _URC_CONTINUE_UNWIND;

    if (actions & _UA_SEARCH_PHASE)
        return _URC_HANDLER_FOUND;

    if (actions & _UA_HANDLER_FRAME) {
        /* NB: (_Unwind_Word) i.p.v. (long): geen 32-bit truncatie op Windows. */
        _Unwind_SetGR(context, 0, (_Unwind_Word)ue_header);   /* RAX = exception */
        _Unwind_SetGR(context, 1, (_Unwind_Word)selector);    /* RDX = selector */
        _Unwind_SetIP(context, landing_pad);
        return _URC_INSTALL_CONTEXT;
    }

    return _URC_CONTINUE_UNWIND;
}

/* Rauwe 4-arg SEH-handler (in .xdata geregistreerd); stuurt door naar
   _GCC_specific_handler met onze Itanium-persoonlijkheid als 5e arg. */
#if defined(_WIN32) && (defined(__GNUC__) || defined(__clang__))
EXCEPTION_DISPOSITION
nabu_seh_personality(PEXCEPTION_RECORD msexc, void *frame,
                     PCONTEXT msctx, PDISPATCHER_CONTEXT disp) {
    return _GCC_specific_handler(msexc, frame, msctx, disp,
                                 nabu_itanium_personality);
}
#endif

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
 * String-concat
 * ------------------------------------------------------- */

char *nabu_concat(const char *a, const char *b) {
    if (a == NULL) a = "";
    if (b == NULL) b = "";

    size_t la = strlen(a);
    size_t lb = strlen(b);

    char *out = malloc(la + lb + 1);
    if (out == NULL) {
        fprintf(stderr, "nabu: kan geen geheugen alloceren voor concat\n");
        abort();
    }

    memcpy(out, a, la);
    memcpy(out + la, b, lb + 1);
    return out;
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

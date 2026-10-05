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
 * MSVC-CRT wordt niet ondersteund
 * -------------------------------------------------------
 *
 * De exception-ondersteuning hieronder is Itanium-ABI-gebaseerd:
 * nabu_seh_personality leest de LSDA (.gcc_except_table) die LLVM in
 * .xdata zet en installeert het landingpad met RAX/RDX. Een MSVC-CRT heeft
 * daarvoor een ander mechanisme: x64 *scope tables* met
 * __C_specific_handler, en __gcc_personality_v0 bestaat daar niet eens.
 *
 * We controleren dit op _MSC_VER en niet op het ontbreken van __GNUC__/
 * __clang__: clang definieert __clang__ óók bij -target *-msvc, waardoor de
 * MSVC-target stilletjes de GCC-tak binnen zou lopen en pas veel later op
 * _GCC_specific_handler stuklopen. _MSC_VER wordt zowel door cl.exe als door
 * clang op een MSVC-target gezet, en dekt dus beide.
 *
 * Ondersteund voor Windows is het MinGW/SEH-pad (gcc/clang met de
 * Itanium-ABI), bijvoorbeeld target x86_64-w64-windows-gnu. Zie ook
 * Linker.msvcUnsupported() in de native-backend.
 */

#ifdef _MSC_VER
#error "nabu_runtime.c ondersteunt geen MSVC-CRT: de exception-personality is \
Itanium-ABI-gebaseerd (__gcc_personality_v0 + LSDA in .xdata) en bestaat niet \
in MSVC, dat x64 scope tables met __C_specific_handler gebruikt. Bouw voor \
Windows met een GCC/Clang-toolchain en het MinGW/SEH-target \
(x86_64-w64-windows-gnu)."
#endif

/* -------------------------------------------------------
 * Reflectie-metadata registry (fase 2): velden + annotaties
 * ------------------------------------------------------- */

#define NABU_MAX_REFLECTION 64

static const nabu_reflection_info *nabu_reflection_table[NABU_MAX_REFLECTION];
static int nabu_reflection_count = 0;

int nabu_register_reflection(const nabu_reflection_info *info) {
    if (info == NULL || info->type_name == NULL) {
        return 0;
    }
    if (nabu_reflection_count >= NABU_MAX_REFLECTION) {
        fprintf(stderr, "nabu: te veel reflectie-metadata entries\n");
        abort();
    }
    nabu_reflection_table[nabu_reflection_count++] = info;
    return 1;
}

const nabu_reflection_info *nabu_lookup_reflection(const char *internal_name) {
    if (internal_name == NULL) {
        return NULL;
    }
    for (int i = 0; i < nabu_reflection_count; i++) {
        if (strcmp(nabu_reflection_table[i]->type_name, internal_name) == 0) {
            return nabu_reflection_table[i];
        }
    }
    return NULL;
}

intptr_t nabu_reflect_field_offset(const char *type_name, const char *field_name) {
    const nabu_reflection_info *info = nabu_lookup_reflection(type_name);
    if (info == NULL || field_name == NULL) {
        return -1;
    }
    for (int i = 0; i < info->field_count; i++) {
        if (strcmp(info->fields[i].name, field_name) == 0) {
            return (intptr_t)info->fields[i].offset;
        }
    }
    return -1;
}

const nabu_annotation_entry *nabu_get_annotation(const char *type_name,
                                                 const char *annotation_type_name) {
    const nabu_reflection_info *info = nabu_lookup_reflection(type_name);
    if (info == NULL || annotation_type_name == NULL) {
        return NULL;
    }
    for (int i = 0; i < info->annotation_count; i++) {
        const nabu_annotation_entry *entry = &info->annotations[i];
        if (entry->owner == NULL
                && strcmp(entry->type_name, annotation_type_name) == 0) {
            return entry;
        }
    }
    return NULL;
}

const nabu_annotation_entry *nabu_get_field_annotation(const char *type_name,
                                                       const char *field_name,
                                                       const char *annotation_type_name) {
    const nabu_reflection_info *info = nabu_lookup_reflection(type_name);
    if (info == NULL || field_name == NULL || annotation_type_name == NULL) {
        return NULL;
    }
    for (int i = 0; i < info->annotation_count; i++) {
        const nabu_annotation_entry *entry = &info->annotations[i];
        if (entry->owner != NULL
                && strcmp(entry->owner, field_name) == 0
                && strcmp(entry->type_name, annotation_type_name) == 0) {
            return entry;
        }
    }
    return NULL;
}

/* -------------------------------------------------------
 * Reflectie: getypte veldtoegang (onderlegger voor een lite
 * java.lang.reflect.Field). De offset komt uit de geregistreerde
 * reflectie-metadata.
 * ------------------------------------------------------- */

static size_t nabu_field_offset_for(void *obj,
                                    const char *type_name,
                                    const char *field_name) {
    if (obj == NULL || type_name == NULL || field_name == NULL) {
        return (size_t)-1;
    }
    intptr_t offset = nabu_reflect_field_offset(type_name, field_name);
    return offset < 0 ? (size_t)-1 : (size_t)offset;
}

int nabu_reflect_get_i32(void *obj, const char *type_name, const char *field_name) {
    size_t off = nabu_field_offset_for(obj, type_name, field_name);
    if (off == (size_t)-1) return 0;
    int value;
    memcpy(&value, (char *)obj + off, sizeof(value));
    return value;
}

void nabu_reflect_set_i32(void *obj, const char *type_name, const char *field_name, int value) {
    size_t off = nabu_field_offset_for(obj, type_name, field_name);
    if (off == (size_t)-1) return;
    memcpy((char *)obj + off, &value, sizeof(value));
}

int64_t nabu_reflect_get_i64(void *obj, const char *type_name, const char *field_name) {
    size_t off = nabu_field_offset_for(obj, type_name, field_name);
    if (off == (size_t)-1) return 0;
    int64_t value;
    memcpy(&value, (char *)obj + off, sizeof(value));
    return value;
}

void nabu_reflect_set_i64(void *obj, const char *type_name, const char *field_name, int64_t value) {
    size_t off = nabu_field_offset_for(obj, type_name, field_name);
    if (off == (size_t)-1) return;
    memcpy((char *)obj + off, &value, sizeof(value));
}

float nabu_reflect_get_f32(void *obj, const char *type_name, const char *field_name) {
    size_t off = nabu_field_offset_for(obj, type_name, field_name);
    if (off == (size_t)-1) return 0.0f;
    float value;
    memcpy(&value, (char *)obj + off, sizeof(value));
    return value;
}

void nabu_reflect_set_f32(void *obj, const char *type_name, const char *field_name, float value) {
    size_t off = nabu_field_offset_for(obj, type_name, field_name);
    if (off == (size_t)-1) return;
    memcpy((char *)obj + off, &value, sizeof(value));
}

double nabu_reflect_get_f64(void *obj, const char *type_name, const char *field_name) {
    size_t off = nabu_field_offset_for(obj, type_name, field_name);
    if (off == (size_t)-1) return 0.0;
    double value;
    memcpy(&value, (char *)obj + off, sizeof(value));
    return value;
}

void nabu_reflect_set_f64(void *obj, const char *type_name, const char *field_name, double value) {
    size_t off = nabu_field_offset_for(obj, type_name, field_name);
    if (off == (size_t)-1) return;
    memcpy((char *)obj + off, &value, sizeof(value));
}

void *nabu_reflect_get_ref(void *obj, const char *type_name, const char *field_name) {
    size_t off = nabu_field_offset_for(obj, type_name, field_name);
    if (off == (size_t)-1) return NULL;
    void *value;
    memcpy(&value, (char *)obj + off, sizeof(value));
    return value;
}

void nabu_reflect_set_ref(void *obj, const char *type_name, const char *field_name, void *value) {
    size_t off = nabu_field_offset_for(obj, type_name, field_name);
    if (off == (size_t)-1) return;
    memcpy((char *)obj + off, &value, sizeof(value));
}

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
 * Initialiseert de object-header van een object van een onbekende (library)-
 * klasse met geldige type-info, zodat nabu_instanceof/nabu_can_catch erop
 * werken. De type-info wordt per interne naam gecachet.
 *
 * De allocatie zelf doet de backend: alleen die weet welke GC-strategie geldt
 * (GC_malloc / malloc / stack). Zie de commentaar in nabu_runtime.h.
 */
void *nabu_init_object(void *obj, const char *type_name) {
    nabu_object *header = (nabu_object *)obj;
    /* Alleen de header nulzetten: het gedeelte daachter zijn instantievelden
     * waarvan de backend de grootte en betekenis niet kent. */
    memset(header, 0, sizeof(nabu_object));
    header->type = nabu_type_info_for(type_name);
    return header;
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

/**
 * Vervolg de unwind van de exception die het landingspad in deze frame
 * bereikte. Zie nabu_runtime.h.
 */
void nabu_resume(void *unwind_exc) {
    struct _Unwind_Exception *ue = (struct _Unwind_Exception *)unwind_exc;

#if defined(_WIN32)
    /* Windows x64 gebruikt SEH, niet het ene Itanium-DWARF-unwind-model. Zodra
     * de SEH-handler van deze frame eenmaal is gevestigd (nabu_seh_personality
     * gaf HANDLER_FOUND en RtlUnwindEx sprong naar het landingspad), spoelt
     * _Unwind_Resume terug naar datzelfde landingspad — de persoonlijkheid wordt
     * niet opnieuw geraadpleegd, dus de type-check in code loopt weer nabu_resume
     * aan: een oneindige lus (empirisch: nabu_catch/nabu_resume-beurten met
     * dezelfde unwind-pointer, 100% CPU).
     *
     * Een verse _Unwind_RaiseException vanaf het landingspad-IP begint een
     * nieuwe zoektocht bóven deze frame: het landingspad zelf ligt buiten alle
     * call-site-ranges van het LSDA (het is géén beschermde regio), dus de
     * persoonlijkheid vervolgt de unwind. Omringende handlers (callers, en
     * meestal ook omringende try-regio's in dezelfde functie, afhankelijk van
     * de blokvolgorde) worden gevonden; is er geen, dan keert nabu_throw terug
     * en aborteren we netjes ("onbehandelde exception") i.p.v. te hangen.
     */
    nabu_unwind_exception *exc = (nabu_unwind_exception *)ue;
    nabu_throw(exc->nabu_object);
    abort(); /* nabu_throw keert alleen terug zonder handler */
#else
    _Unwind_Resume(ue);
    /* _Unwind_Resume keert alleen terug wanneer er nergens in de stack een
       handler is; dan is de exception onbehandeld. */
    fprintf(stderr, "nabu: onbehandelde exception (resume zonder handler)\n");
    abort();
#endif
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
 * Zonder GCC/Clang-ABI
 * -------------------------------------------------------
 *
 * MSVC is hierboven al afgewezen via #error (_MSC_VER). De Itanium-ABI-tak
 * hierboven geldt dus voor Linux, macOS, MinGW en voor elke andere
 * GCC/Clang-toolchain; de #else is alleen bereikbaar als er geen GCC- en geen
 * Clang-ABI is, en dan weten we niet welk unwinding-mechanisme geldt. Faal
 * daarom in plaats van een runtime te bouwen die nabu_throw stilzwijgend
 * afbreekt.
 */

#error "nabu_runtime.c heeft een GCC- of Clang-toolchain nodig voor exception \
unwinding (Itanium-ABI); geen herkenbare ABI op dit platform. Op Windows: \
gebruik MinGW/gcc met target x86_64-w64-windows-gnu."

#endif

/* -------------------------------------------------------
 * Reference counting (GcStrategy.REFCOUNT)
 * -------------------------------------------------------
 *
 * De teller zit in de object-header (offset 24, zie nabu_object). De backend
 * alloceert met malloc en wijst zelf type/vtable/itable in de header, dus
 * free() op het object-adres is correct. De teller wordt door de backend op 0
 * gezet bij de allocatie; malloc geeft immers ongeïnitialiseerd geheugen terug.
 *
 * Bewust géén finalizer-traversaal: zodra een object door een ander object
 * bereikbaar is (object-graph, en dus ook een cyclus) wordt het niet meer
 * gezweept. De backend voorkomt wél *vroegtijdig* vrijgeven: een object dat
 * uit de frame ontsnapt (returnwaarde, object-veld, array-element, call-
 * argument, throw) krijgt geen retain en wordt door de exit-pad overgeslagen.
 * Zo'n object is daarmee ongecounterd en lekt — de ontvanger zou het moeten
 * beheren en de backend weet niet hoe.
 */

static long nabu_rc_retains  = 0;
static long nabu_rc_releases = 0;

void nabu_retain(void *obj) {
    if (obj == NULL) {
        return;
    }
    nabu_rc_retains++;
    ((nabu_object *)obj)->ref_count++;
}

void nabu_release(void *obj) {
    if (obj == NULL) {
        return;
    }
    nabu_rc_releases++;
    nabu_object *header = (nabu_object *)obj;
    /* Een teller van 0 (of negatief, door een bug in de gegenereerde code)
     * betekent dat de eigendom al is ingeleverd. Vrijgeven zou dan een
     * dubbele free zijn, dus hier stopt het: het object lekt liever dan dat
     * de heap kapotgaat. De backend hoort elke retain precies een release te
     * geven, dus dit pad hoort in een correct programma niet voor te komen. */
    if (header->ref_count <= 0) {
        return;
    }
    header->ref_count--;
    if (header->ref_count == 0) {
        free(obj);
    }
}

int nabu_ref_count(void *obj) {
    if (obj == NULL) {
        return -1;
    }
    return (int) ((nabu_object *)obj)->ref_count;
}

long nabu_rc_retain_count(void) {
    return nabu_rc_retains;
}

long nabu_rc_release_count(void) {
    return nabu_rc_releases;
}

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

/* Java-semantiek: "" + int levert de decimale representatie, zonder teken voor
 * positieve getallen. snprintf in de C11-buffer is de kortste vorm; de runtime
 * ondersteunt ook oudere MSVC, dus val terug op een eigen buffer. */
char *nabu_int_to_string(int32_t value) {
    char buf[16];
    int n = snprintf(buf, sizeof(buf), "%d", value);
    if (n < 0) n = 0;
    char *out = malloc((size_t) n + 1);
    if (out == NULL) {
        fprintf(stderr, "nabu: kan geen geheugen alloceren voor int->string\n");
        abort();
    }
    memcpy(out, buf, (size_t) n + 1);
    return out;
}

char *nabu_long_to_string(int64_t value) {
    char buf[24];
    int n = snprintf(buf, sizeof(buf), "%lld", (long long) value);
    if (n < 0) n = 0;
    char *out = malloc((size_t) n + 1);
    if (out == NULL) {
        fprintf(stderr, "nabu: kan geen geheugen alloceren voor long->string\n");
        abort();
    }
    memcpy(out, buf, (size_t) n + 1);
    return out;
}

/* Java-semantiek van Double.toString: altijd een decimaalteken of exponent, en
 * NaN/Infinity met een hoofdletter. %g levert "1" voor 1.0, dus daarachter een
 * ".0" toevoegen. */
char *nabu_double_to_string(double value) {
    char buf[40];
    if (value != value) {                       /* NaN */
        snprintf(buf, sizeof(buf), "NaN");
    } else if (value > 1.7976931348623157e308) { /* +Infinity */
        snprintf(buf, sizeof(buf), "Infinity");
    } else if (value < -1.7976931348623157e308) {/* -Infinity */
        snprintf(buf, sizeof(buf), "-Infinity");
    } else {
        int n = snprintf(buf, sizeof(buf), "%g", value);
        if (n < 0) n = 0;
        if (strpbrk(buf, ".eE") == NULL) {
            size_t len = strlen(buf);
            if (len + 3 < sizeof(buf)) {
                buf[len] = '.';
                buf[len + 1] = '0';
                buf[len + 2] = '\0';
            }
        }
    }
    size_t len = strlen(buf);
    char *out = malloc(len + 1);
    if (out == NULL) {
        fprintf(stderr, "nabu: kan geen geheugen alloceren voor double->string\n");
        abort();
    }
    memcpy(out, buf, len + 1);
    return out;
}

char *nabu_float_to_string(float value) {
    return nabu_double_to_string((double) value);
}

char *nabu_bool_to_string(int32_t value) {
    const char *text = value ? "true" : "false";
    size_t len = strlen(text);
    char *out = malloc(len + 1);
    if (out == NULL) {
        fprintf(stderr, "nabu: kan geen geheugen alloceren voor bool->string\n");
        abort();
    }
    memcpy(out, text, len + 1);
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

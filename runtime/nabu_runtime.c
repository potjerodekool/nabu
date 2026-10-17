/**
 * Nabu Runtime Helpers â€” implementatie.
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
 * __clang__: clang definieert __clang__ Ã³Ã³k bij -target *-msvc, waardoor de
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
 * Unwind exception wrapper â€” bevat de Itanium ABI _Unwind_Exception
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
   backend-string-globals, dus de pointers blijven geldig. De array staat op
   file-scope zodat nabu_jcl_is_object de range kan toetsen (jcl-lite wil
   weten of een `void *`-argument een library-object óf een raw char* is:
   library-objects hebben hun type-pointer ín deze cache, string-literals
   staan in .rodata). */
#define NABU_MAX_TYPES 1024

static nabu_type_info nabu_type_cache[NABU_MAX_TYPES];
static int nabu_type_count = 0;

/**
 * Ingebouwde super-keten voor de kern java.lang/java.io exception-klassen
 * die de JCL-lite runtime (runtime/jcl_lite.c) zelf gooit via
 * jcl_throw/jcl_fill_throw met een hardcoded 'super'-naam (bijna altijd
 * "java/lang/RuntimeException", soms een tussenliggende klasse zoals
 * "java/lang/IndexOutOfBoundsException"). Die hardcoded super-naam wees op
 * een VERSE cache-entry die zelf nooit verder werd doorgekoppeld naar
 * Exception/Throwable: de keten stopte dus altijd bij RuntimeException.
 * Elke `catch (Exception e)` of `catch (Throwable t)` op een ingebouwde
 * runtime-exception faalde zo stilzwijgend via nabu_instanceof (geen
 * bezochte naam matchte "java/lang/Exception"/"java/lang/Throwable"),
 * nabu_can_catch gaf 0 terug, de exception werd ge-resumed in plaats van
 * gevangen, en liep — zonder een buitenste catch die wél exact op de
 * concrete klasse matcht — af tot een onbehandelde exception (abort).
 *
 * Deze tabel vult alleen de ONTBREKENDE schakel aan: bij het aanmaken van
 * een VERSE cache-entry voor een van deze basis-namen wordt diens eigen
 * super_type meteen goedgezet (recursief via nabu_type_info_for, dus de
 * keten loopt vanzelf door tot aan Throwable). Een expliciete super die een
 * aanroeper van nabu_init_object_ex zelf meegeeft blijft die ALTIJD winnen
 * (die overschrijft super_type na deze aanmaak gewoon opnieuw) — dit is dus
 * puur een betere default, geen vervanging van de bestaande logica.
 */
static const char *nabu_builtin_super_of(const char *name) {
    if (strcmp(name, "java/lang/RuntimeException") == 0) return "java/lang/Exception";
    if (strcmp(name, "java/lang/Exception") == 0) return "java/lang/Throwable";
    if (strcmp(name, "java/lang/Error") == 0) return "java/lang/Throwable";
    if (strcmp(name, "java/lang/IndexOutOfBoundsException") == 0) return "java/lang/RuntimeException";
    if (strcmp(name, "java/io/IOException") == 0) return "java/lang/Exception";
    if (strcmp(name, "java/lang/ReflectiveOperationException") == 0) return "java/lang/Exception";
    return NULL;
}

static nabu_type_info *nabu_type_info_for(const char *name) {
    for (int i = 0; i < nabu_type_count; i++) {
        if (strcmp(nabu_type_cache[i].name, name) == 0)
            return &nabu_type_cache[i];
    }
    if (nabu_type_count >= NABU_MAX_TYPES) {
        fprintf(stderr, "nabu: te veel runtime-type-info entries\n");
        abort();
    }
    nabu_type_info *ti = &nabu_type_cache[nabu_type_count++];
    ti->name = name;
    ti->size = sizeof(nabu_object);
    ti->field_count = 0;
    /* Eerst NULL: de recursieve nabu_type_info_for-aanroep hieronder (voor
     * een bekende basisklasse) mag nooit op deze eigen, nog-niet-klare
     * entry stuiten (kan toch niet, de naam verschilt altijd, maar NULL is
     * ook de juiste default als er geen bekende basisklasse-super is). */
    ti->super_type = NULL;
    const char *builtinSuper = nabu_builtin_super_of(name);
    if (builtinSuper != NULL) {
        ti->super_type = nabu_type_info_for(builtinSuper);
    }
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
/* De vtable van het gecompileerde java.lang.Object (zie
 * nabu_set_object_vtable); NULL zolang het programma die niet registreert. */
static void *nabu_object_vtable = NULL;

/**
 * Registreert de vtable van java.lang.Object. De backend roept dit aan vanuit
 * een vroege module-constructor, vóór elke <clinit>.
 *
 * Library-objecten (jcl-lite) hebben geen eigen vtable, maar belanden wél
 * achter een Object-referentie, en een virtuele aanroep daarop (equals,
 * toString) leest de functiepointer uit header->vtable. Met NULL daar sprong
 * zo'n aanroep naar een willekeurig adres; met Object's vtable krijgen ze het
 * Object-gedrag, zoals een Java-klasse zonder eigen override.
 */
void nabu_set_object_vtable(void *vtable) {
    nabu_object_vtable = vtable;
}

/* De library-itable van de backend: per interface-slot de functie van de
 * interface zelf (C-implementatie, default-body of abstracte stub). Zelfde
 * reden als de vtable: een interface-aanroep op een library-object las anders
 * zijn functiepointer uit een NULL-itable. */
static void *nabu_object_itable = NULL;

void nabu_set_object_itable(void *itable) {
    nabu_object_itable = itable;
}

/* De type-info van elke gecompileerde klasse (geen interfaces), geregistreerd
 * door de backend; voor opzoeken op naam (Class.getSuperclass). */
static const nabu_type_info *const *nabu_compiled_types = NULL;
static int32_t nabu_compiled_type_count = 0;

void nabu_register_type_infos(const void *table, int32_t count) {
    nabu_compiled_types = (const nabu_type_info *const *)table;
    nabu_compiled_type_count = count;
}

const nabu_type_info *nabu_compiled_type_for(const char *internal_name) {
    if (internal_name == NULL) return NULL;
    for (int32_t i = 0; i < nabu_compiled_type_count; i++) {
        const nabu_type_info *ti = nabu_compiled_types[i];
        if (ti != NULL && ti->name != NULL && strcmp(ti->name, internal_name) == 0) return ti;
    }
    return NULL;
}

intptr_t nabu_instance_field_offset(const void *obj, const char *name) {
    if (obj == NULL || name == NULL || nabu_compiled_type_name(obj) == NULL) return -1;
    for (const nabu_type_info *ti = ((const nabu_object *)obj)->type; ti != NULL; ti = ti->super_type) {
        for (int i = 0; i < ti->instance_field_count; i++) {
            if (strcmp(ti->instance_fields[i].name, name) == 0) {
                return (intptr_t)ti->instance_fields[i].offset;
            }
        }
    }
    return -1;
}

void *nabu_init_object(void *obj, const char *type_name) {
    nabu_object *header = (nabu_object *)obj;
    /* Header nulzetten plus het state-slot direct erachter (8 bytes; zie
     * JCL_STATE_SLOT in de backend). Dat slot is leeg zolang de runtime er
     * geen eigen struct in hangt — de betekenis van de bytes dáárachter is
     * aan de library-implementatie (runtime/jcl_lite.c), niet aan ons. */
    memset(header, 0, sizeof(nabu_object) + sizeof(void *));
    header->type = nabu_type_info_for(type_name);
    header->vtable = nabu_object_vtable;
    header->itable = nabu_object_itable;
    return header;
}

/**
 * Als nabu_init_object, maar met een expliciete super-klasse. Nodig voor
 * exception-objecten: de super-keten (NumberFormatException ->
 * IllegalArgumentException -> ...) bepaalt of `catch (Exception)` vangt via
 * nabu_instanceof. Super mag NULL zijn.
 */
void *nabu_init_object_ex(void *obj, const char *type_name, const char *super_name) {
    nabu_object *header = (nabu_object *)nabu_init_object(obj, type_name);
    ((nabu_type_info *)header->type)->super_type =
            super_name != NULL ? nabu_type_info_for(super_name) : NULL;
    return header;
}

/**
 * Of `p` een library-object is (jcl-lite-object: header->type wijst ín de
 * runtime-type-cache) en dus géén raw char*-literal.
 *
 * Heuristiek voor jcl-lite's gemengde argument-conventie: String-format-args,
 * map-keys e.d. mengen boxed objecten met `char *`-literals. Een string-
 * literal gelezen als type-pointer geeft ASCII-bytes als adres — dat valt
 * buiten het cache-bereik. Leest geen geheugen buiten `p` zelf (alleen de
 * eerste 8 bytes), dus ook veilig voor literals.
 */
int nabu_jcl_is_object(const void *p) {
    if (p == NULL) return 0;
    const void *t = ((const void *const *)p)[0];
    if (t < (const void *)nabu_type_cache
            || t >= (const void *)(nabu_type_cache + NABU_MAX_TYPES)) {
        return 0;
    }
    return ((size_t)((const char *)t - (const char *)nabu_type_cache)
            % sizeof(nabu_type_info)) == 0;
}

/* ---------------------------------------------------------------
 * Library-object state-slot (offset 40; zie JCL_STATE_SLOT in de backend)
 * --------------------------------------------------------------- */

void *nabu_jcl_state(void *obj) {
    if (obj == NULL) return NULL;
    return ((void **)obj)[5];
}

void nabu_jcl_set_state(void *obj, void *state) {
    if (obj == NULL) return;
    ((void **)obj)[5] = state;
}

/**
 * Constructor van java.lang.Exception (en objecten met lege layout).
 * De runtime voorziet de definitie zodat het externe symbool resolvet.
 */
void java_lang_Exception_init(void *self) {
    (void)self; /* geen instantievelden in de minimale layout */
}

/**
 * Cleanup callback â€” wordt door de unwinder aangeroepen wanneer de
 * exception niet meer nodig is (bijv. na een catch of wanneer geen
 * handler gevonden is).
 */
static void nabu_exception_cleanup(_Unwind_Reason_Code reason,
                                   struct _Unwind_Exception *exc) {
    (void)reason;
    nabu_unwind_exception *nabu_exc = (nabu_unwind_exception *)exc;
    free(nabu_exc);
}

/* Throwable.getMessage van het programma (zie nabu_set_throwable_message_fn);
 * alleen voor de melding bij een onbehandelde exception. */
static void *(*nabu_throwable_message_fn)(void *) = NULL;

void nabu_set_throwable_message_fn(void *fn) {
    nabu_throwable_message_fn = (void *(*)(void *))fn;
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
    if (exception != NULL) {
        nabu_type_info *ti = ((nabu_object *)exception)->type;
        if (ti != NULL && ti->name != NULL) {
            fprintf(stderr, "nabu: exception-klasse: %s\n", ti->name);
            /* Alleen voor gecompileerde Throwables: een library-exception
             * (jcl-object) heeft de velden van Throwable niet. */
            if (nabu_throwable_message_fn != NULL && !nabu_jcl_is_object(exception)) {
                void *msg = nabu_throwable_message_fn(exception);
                if (msg != NULL) {
                    fprintf(stderr, "nabu: message: %s\n", nabu_cstr(msg));
                }
            }
        } else {
            fprintf(stderr, "nabu: exception-klasse: <onbekend, type=%p>\n", (void *)ti);
        }
    } else {
        fprintf(stderr, "nabu: exception-object is NULL\n");
    }
    fflush(stderr);
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
     * _Unwind_Resume terug naar datzelfde landingspad â€” de persoonlijkheid wordt
     * niet opnieuw geraadpleegd, dus de type-check in code loopt weer nabu_resume
     * aan: een oneindige lus (empirisch: nabu_catch/nabu_resume-beurten met
     * dezelfde unwind-pointer, 100% CPU).
     *
     * Een verse _Unwind_RaiseException vanaf het landingspad-IP begint een
     * nieuwe zoektocht bÃ³ven deze frame: het landingspad zelf ligt buiten alle
     * call-site-ranges van het LSDA (het is gÃ©Ã©n beschermde regio), dus de
     * persoonlijkheid vervolgt de unwind. Omringende handlers (callers, en
     * meestal ook omringende try-regio's in dezelfde functie, afhankelijk van
     * de blokvolgorde) worden gevonden; is er geen, dan keert nabu_throw terug
     * en aborteren we netjes ("onbehandelde exception") i.p.v. te hangen.
     */
    /* unwind_exc is hier GEEN nabu_unwind_exception-wrapper meer: de backend
     * (FunctionEmitter.emitLandingPadChain) heeft 'm al door nabu_catch()
     * gehaald voordat hij nabu_resume aanroept, dus dit IS al het kale
     * nabu-object. 'm hier opnieuw als wrapper casten en ->nabu_object
     * uitlezen (de oude code) leest een willekeurig geheugenoffset binnen
     * dat object (of erna) en gaf dat als "nieuwe" exception aan nabu_throw
     * mee — vandaar een compleet willekeurig/ongeldig object bij een
     * doorgegeven (niet-gevangen) exception. Gewoon doorgeven dus. */
    (void)ue;
    nabu_throw(unwind_exc);
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
 * gezet bij de allocatie; malloc geeft immers ongeÃ¯nitialiseerd geheugen terug.
 *
 * Bewust gÃ©Ã©n finalizer-traversaal: zodra een object door een ander object
 * bereikbaar is (object-graph, en dus ook een cyclus) wordt het niet meer
 * gezweept. De backend voorkomt wÃ©l *vroegtijdig* vrijgeven: een object dat
 * uit de frame ontsnapt (returnwaarde, object-veld, array-element, call-
 * argument, throw) krijgt geen retain en wordt door de exit-pad overgeslagen.
 * Zo'n object is daarmee ongecounterd en lekt â€” de ontvanger zou het moeten
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

const char *nabu_compiled_type_name(const void *p) {
#ifdef _WIN32
    static const char *image_lo = NULL, *image_hi = NULL;
    if (p == NULL) return NULL;
    if (image_lo == NULL) {
        MEMORY_BASIC_INFORMATION mbi;
        HMODULE mod = GetModuleHandleA(NULL);
        image_lo = (const char *) mod;
        image_hi = image_lo;
        while (VirtualQuery(image_hi, &mbi, sizeof mbi) == sizeof mbi
                && mbi.AllocationBase == (void *) mod) {
            image_hi = (const char *) mbi.BaseAddress + mbi.RegionSize;
        }
    }
    /* Een waarde in het image zelf is een constante (C-string, tabel); een
     * object-header daar hoort alleen bij een statisch String-object, en dat
     * herkent nabu_is_jstring al. */
    const char *t = ((const char *const *) p)[0];
    if (t < image_lo || t >= image_hi) return NULL;
    const char *name = ((const nabu_type_info *) t)->name;
    if (name < image_lo || name >= image_hi) return NULL;
    return name;
#else
    (void) p;
    return NULL;
#endif
}

int nabu_instanceof(void *obj, const char *type_name) {
    if (obj == NULL) {
        return 0;
    }
#ifdef _WIN32
    /* Geen object met een header (een kale C-string uit de C-runtime, bv. een
     * klassenaam die als Class dient): dan is het van geen enkel type een
     * instantie, in plaats van de tekst als type-info uit te lezen. */
    if (!nabu_jcl_is_object(obj) && nabu_compiled_type_name(obj) == NULL) {
        return 0;
    }
#endif

    nabu_object *header = (nabu_object *)obj;
    nabu_type_info *current = header->type;

    while (current != NULL) {
        if (strcmp(current->name, type_name) == 0) {
            return 1;
        }
        for (int i = 0; i < current->interface_count; i++) {
            if (strcmp(current->interfaces[i], type_name) == 0) {
                return 1;
            }
        }
        current = current->super_type;
    }

    return 0;
}

/* -------------------------------------------------------
 * String-concat
 * ------------------------------------------------------- */

#ifdef NABU_GC_BOEHM
#include <gc.h>
#define NABU_STR_MEM(n) GC_malloc(n)
#else
#define NABU_STR_MEM(n) calloc(1, (n))
#endif

/* Spiegel van de layout die de backend voor java.lang.String emitteert:
 * header + de instantievelden in classfile-volgorde. */
typedef struct nabu_jstring {
    nabu_object header;
    void   *value;        /* byte[]: int32 lengte op 0, data op 8 */
    int8_t  coder;        /* 0 = LATIN1, 1 = UTF16 (little endian) */
    int32_t hash;
    int8_t  hash_is_zero;
} nabu_jstring;

static void *nabu_str_mem(size_t n) {
    void *p = NABU_STR_MEM(n);
    if (p == NULL) {
        fprintf(stderr, "nabu: kan geen geheugen alloceren voor een string\n");
        abort();
    }
    return p;
}

/* Een virtuele/interface-aanroep op null (zie InstructionEmitter.
 * emitReceiverNullCheck): gooit java.lang.NullPointerException. `site`
 * ("callee in functie") wordt gemeld als NABU_TRACE_NPE gezet is. */
void nabu_throw_npe(const char *site) {
    static int trace = -1;
    if (trace < 0) trace = getenv("NABU_TRACE_NPE") != NULL;
    if (trace) fprintf(stderr, "nabu: NullPointerException: %s\n", site != NULL ? site : "?");
    void *obj = nabu_str_mem(sizeof(nabu_object) + 2 * sizeof(void *));
    nabu_init_object_ex(obj, "java/lang/NullPointerException", "java/lang/RuntimeException");
    nabu_throw(obj);
    fprintf(stderr, "nabu: onbehandelde NullPointerException\n");
    abort();
}

int nabu_is_jstring(const void *p) {
    return p != NULL && nabu_string_meta.type != NULL
            && ((const void *const *) p)[0] == nabu_string_meta.type;
}

static size_t nabu_utf8_put(char *out, uint32_t cp) {
    if (cp < 0x80) { out[0] = (char) cp; return 1; }
    if (cp < 0x800) {
        out[0] = (char) (0xC0 | (cp >> 6));
        out[1] = (char) (0x80 | (cp & 0x3F));
        return 2;
    }
    if (cp < 0x10000) {
        out[0] = (char) (0xE0 | (cp >> 12));
        out[1] = (char) (0x80 | ((cp >> 6) & 0x3F));
        out[2] = (char) (0x80 | (cp & 0x3F));
        return 3;
    }
    out[0] = (char) (0xF0 | (cp >> 18));
    out[1] = (char) (0x80 | ((cp >> 12) & 0x3F));
    out[2] = (char) (0x80 | ((cp >> 6) & 0x3F));
    out[3] = (char) (0x80 | (cp & 0x3F));
    return 4;
}

const char *nabu_cstr(const void *p) {
    if (p == NULL) return "";
    if (!nabu_is_jstring(p)) return (const char *) p;

    const nabu_jstring *js = (const nabu_jstring *) p;
    if (js->value == NULL) return "";
    int32_t byte_len = *(const int32_t *) js->value;
    const unsigned char *data = (const unsigned char *) js->value + 8;
    if (byte_len < 0) byte_len = 0;

    /* Ruimste geval: elk LATIN1-byte wordt 2 bytes, elke UTF16-unit 3. */
    char *out = (char *) nabu_str_mem((size_t) byte_len * 2 + 1);
    size_t n = 0;
    if (js->coder == 0) {
        for (int32_t i = 0; i < byte_len; i++) n += nabu_utf8_put(out + n, data[i]);
    } else {
        int32_t units = byte_len / 2;
        for (int32_t i = 0; i < units; i++) {
            uint32_t cp = (uint32_t) data[2 * i] | ((uint32_t) data[2 * i + 1] << 8);
            if (cp >= 0xD800 && cp < 0xDC00 && i + 1 < units) {
                uint32_t lo = (uint32_t) data[2 * i + 2] | ((uint32_t) data[2 * i + 3] << 8);
                if (lo >= 0xDC00 && lo < 0xE000) {
                    cp = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00);
                    i++;
                }
            }
            n += nabu_utf8_put(out + n, cp);
        }
    }
    out[n] = 0;
    return out;
}

void *nabu_jstring_new(const char *utf8) {
    if (utf8 == NULL) utf8 = "";
    size_t in_len = strlen(utf8);
    if (nabu_string_meta.type == NULL) {
        char *copy = (char *) nabu_str_mem(in_len + 1);
        memcpy(copy, utf8, in_len + 1);
        return copy;
    }

    /* UTF-8 decoderen naar code points; LATIN1 als alles < 256 is. */
    uint32_t *cps = (uint32_t *) nabu_str_mem((in_len + 1) * sizeof(uint32_t));
    size_t count = 0;
    int latin1 = 1;
    size_t utf16_units = 0;
    const unsigned char *in = (const unsigned char *) utf8;
    for (size_t i = 0; i < in_len;) {
        uint32_t cp = in[i];
        size_t extra = 0;
        if (cp >= 0xF0 && cp < 0xF8) { cp &= 0x07; extra = 3; }
        else if (cp >= 0xE0) { cp &= 0x0F; extra = 2; }
        else if (cp >= 0xC0) { cp &= 0x1F; extra = 1; }
        i++;
        for (; extra > 0 && i < in_len && (in[i] & 0xC0) == 0x80; extra--, i++) {
            cp = (cp << 6) | (in[i] & 0x3F);
        }
        if (cp > 0xFF) latin1 = 0;
        utf16_units += cp >= 0x10000 ? 2 : 1;
        cps[count++] = cp;
    }

    size_t byte_len = latin1 ? count : utf16_units * 2;
    unsigned char *arr = (unsigned char *) nabu_str_mem(8 + byte_len);
    ((int32_t *) arr)[0] = (int32_t) byte_len;
    ((int32_t *) arr)[1] = 1; /* elementgrootte: byte[] */
    unsigned char *data = arr + 8;
    if (latin1) {
        for (size_t i = 0; i < count; i++) data[i] = (unsigned char) cps[i];
    } else {
        size_t o = 0;
        for (size_t i = 0; i < count; i++) {
            uint32_t cp = cps[i];
            if (cp >= 0x10000) {
                cp -= 0x10000;
                uint32_t hi = 0xD800 + (cp >> 10);
                uint32_t lo = 0xDC00 + (cp & 0x3FF);
                data[o++] = (unsigned char) (hi & 0xFF); data[o++] = (unsigned char) (hi >> 8);
                data[o++] = (unsigned char) (lo & 0xFF); data[o++] = (unsigned char) (lo >> 8);
            } else {
                data[o++] = (unsigned char) (cp & 0xFF); data[o++] = (unsigned char) (cp >> 8);
            }
        }
    }

    nabu_jstring *js = (nabu_jstring *) nabu_str_mem(sizeof(nabu_jstring));
    js->header.type = nabu_string_meta.type;
    js->header.vtable = nabu_string_meta.vtable;
    js->header.itable = nabu_string_meta.itable;
    js->value = arr;
    js->coder = latin1 ? 0 : 1;
    return js;
}

char *nabu_concat(const char *a, const char *b) {
    a = nabu_cstr(a);
    b = nabu_cstr(b);
    if (nabu_string_meta.type != NULL) {
        size_t la = strlen(a);
        size_t lb = strlen(b);
        char *joined = (char *) nabu_str_mem(la + lb + 1);
        memcpy(joined, a, la);
        memcpy(joined + la, b, lb + 1);
        return (char *) nabu_jstring_new(joined);
    }

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
    return nabu_string_meta.type != NULL ? (char *) nabu_jstring_new(out) : out;
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
    return nabu_string_meta.type != NULL ? (char *) nabu_jstring_new(out) : out;
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
    return nabu_string_meta.type != NULL ? (char *) nabu_jstring_new(out) : out;
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
    return nabu_string_meta.type != NULL ? (char *) nabu_jstring_new(out) : out;
}

/* -------------------------------------------------------
 * Monitor (synchronized)
 * ------------------------------------------------------- */

#ifdef _WIN32

/* Windows: CRITICAL_SECTION per object (lazy geÃ¯nitialiseerd) */
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
        /* busy wait â€” in productie: use proper condition variable */
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

/* POSIX: pthread_mutex_t per object (lazy geÃ¯nitialiseerd via spinlock) */
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
        /* busy wait â€” in productie: gebruik condition variables */
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

void nabu_unresolved(const char *symbol) {
    fprintf(stderr, "nabu: aanroep van een JDK-functie zonder implementatie: %s\n",
            symbol != NULL ? symbol : "?");
    fflush(stderr);
    abort();
}

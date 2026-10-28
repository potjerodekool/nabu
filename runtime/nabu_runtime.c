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

#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include "nabu_runtime.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <stdint.h>

#include <pthread.h>
#include <sched.h>
#include <time.h>
#ifdef _WIN32
#include <windows.h>
#else
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
        fprintf(stderr, "nabu: too many reflection metadata entries\n");
        abort();
    }
    nabu_reflection_table[nabu_reflection_count++] = info;
    return 1;
}

/* The class table that holds an annotation entry, and the entry's index in
 * it: annotations nested in it are owned by "^index.element". */
const nabu_reflection_info *nabu_annotation_owner(const nabu_annotation_entry *entry, int *index) {
    for (int i = 0; i < nabu_reflection_count; i++) {
        const nabu_reflection_info *info = nabu_reflection_table[i];
        if (info->annotations != NULL && entry >= info->annotations
                && entry < info->annotations + info->annotation_count) {
            if (index != NULL) *index = (int)(entry - info->annotations);
            return info;
        }
    }
    return NULL;
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
        /* A static field has no offset in an object: it lives in a global. */
        if (info->fields[i].static_address == NULL && strcmp(info->fields[i].name, field_name) == 0) {
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

static pthread_mutex_t nabu_rt_mutex;
static pthread_once_t nabu_rt_once = PTHREAD_ONCE_INIT;

static void nabu_rt_init(void) {
    pthread_mutexattr_t attr;
    pthread_mutexattr_init(&attr);
    pthread_mutexattr_settype(&attr, PTHREAD_MUTEX_RECURSIVE);
    pthread_mutex_init(&nabu_rt_mutex, &attr);
}

void nabu_rt_lock(void) {
    pthread_once(&nabu_rt_once, nabu_rt_init);
    pthread_mutex_lock(&nabu_rt_mutex);
}

void nabu_rt_unlock(void) {
    pthread_mutex_unlock(&nabu_rt_mutex);
}

static nabu_type_info *nabu_type_info_for(const char *name) {
    nabu_rt_lock();
    for (int i = 0; i < nabu_type_count; i++) {
        if (strcmp(nabu_type_cache[i].name, name) == 0) {
            nabu_type_info *found = &nabu_type_cache[i];
            nabu_rt_unlock();
            return found;
        }
    }
    if (nabu_type_count >= NABU_MAX_TYPES) {
        fprintf(stderr, "nabu: too many runtime type info entries\n");
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
    nabu_rt_unlock();
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

/* -------------------------------------------------------
 * Stack traces
 *
 * nabu_capture_frames walks the stack with the unwinder (the code has DWARF
 * unwind data, not the Windows .pdata) and keeps the return addresses.
 * nabu_frame_name finds the function of an address in the symbol table of
 * the executable; there is no line information.
 * ------------------------------------------------------- */

typedef struct { uintptr_t ip[256]; int count; int max; int skip; } nabu_frame_walk;

static _Unwind_Reason_Code nabu_frame_cb(struct _Unwind_Context *context, void *arg) {
    nabu_frame_walk *w = (nabu_frame_walk *) arg;
    int before = 0;
    uintptr_t ip = (uintptr_t) _Unwind_GetIPInfo(context, &before);
    if (ip == 0) return _URC_END_OF_STACK;
    if (w->skip > 0) { w->skip--; return _URC_NO_REASON; }
    if (w->count >= w->max) return _URC_END_OF_STACK;
    /* A return address points after the call; the call itself is one byte back. */
    w->ip[w->count++] = before ? ip : ip - 1;
    return _URC_NO_REASON;
}

/* The addresses of the calling frames, innermost first (this function's own frame excluded). */
int nabu_capture_frames(uintptr_t *out, int max) {
    nabu_frame_walk w;
    w.count = 0;
    w.max = max < 256 ? max : 256;
    w.skip = 1;
    _Unwind_Backtrace(nabu_frame_cb, &w);
    for (int i = 0; i < w.count; i++) out[i] = w.ip[i];
    return w.count;
}

#ifdef _WIN32
typedef struct { uintptr_t addr; char *name; } nabu_sym;

static nabu_sym *nabu_syms = NULL;
static size_t nabu_sym_count = 0;
static int nabu_syms_loaded = 0;
static uintptr_t nabu_image_end = 0;

/* The data symbols (globals, such as the static fields of compiled classes), by name. */
static nabu_sym *nabu_dsyms = NULL;
static size_t nabu_dsym_count = 0;

static int nabu_dsym_cmp(const void *a, const void *b) {
    return strcmp(((const nabu_sym *) a)->name, ((const nabu_sym *) b)->name);
}

static int nabu_sym_cmp(const void *a, const void *b) {
    uintptr_t x = ((const nabu_sym *) a)->addr, y = ((const nabu_sym *) b)->addr;
    return x < y ? -1 : x > y ? 1 : 0;
}

#endif /* _WIN32: symbols */

/* -------------------------------------------------------
 * Line numbers
 *
 * The backend gives every function that comes from source a DWARF line table
 * (version 4, see DebugLines). nabu_load_lines reads the .debug_line section
 * of the executable into one table of rows sorted by address. The "directory"
 * of the file of a row is the symbol of the function the line belongs to
 * (which differs from the function around the address when LLVM inlined it).
 * ------------------------------------------------------- */

typedef struct { uintptr_t addr; uint32_t seq; const char *dir; const char *file; int line; } nabu_line_row;

static nabu_line_row *nabu_rows = NULL;
static size_t nabu_row_count = 0;
static size_t nabu_row_cap = 0;

static void nabu_row_add(uintptr_t addr, const char *dir, const char *file, int line) {
    if (nabu_row_count == nabu_row_cap) {
        size_t cap = nabu_row_cap == 0 ? 256 : nabu_row_cap * 2;
        nabu_line_row *p = realloc(nabu_rows, cap * sizeof *p);
        if (p == NULL) return;
        nabu_rows = p;
        nabu_row_cap = cap;
    }
    nabu_line_row *r = &nabu_rows[nabu_row_count];
    r->addr = addr; r->seq = (uint32_t) nabu_row_count; r->dir = dir; r->file = file; r->line = line;
    nabu_row_count++;
}

static uint64_t nabu_uleb(const unsigned char **p, const unsigned char *end) {
    uint64_t v = 0; int shift = 0;
    while (*p < end) {
        unsigned char b = *(*p)++;
        if (shift < 64) v |= (uint64_t) (b & 0x7f) << shift;
        shift += 7;
        if ((b & 0x80) == 0) break;
    }
    return v;
}

static int64_t nabu_sleb(const unsigned char **p, const unsigned char *end) {
    int64_t v = 0; int shift = 0; unsigned char b = 0;
    while (*p < end) {
        b = *(*p)++;
        if (shift < 64) v |= (int64_t) (b & 0x7f) << shift;
        shift += 7;
        if ((b & 0x80) == 0) break;
    }
    if (shift < 64 && (b & 0x40)) v |= -((int64_t) 1 << shift);
    return v;
}

/* Parses the line programs in data[0..size); addresses are moved by `delta` (load address - preferred base). */
static void nabu_parse_lines(const unsigned char *data, size_t size, intptr_t delta) {
    const unsigned char *p = data, *end = data + size;
    while (p + 4 <= end) {
        uint32_t unit_len; memcpy(&unit_len, p, 4); p += 4;
        if (unit_len == 0xffffffffu || p + unit_len > end) return;
        const unsigned char *unit_end = p + unit_len;
        if (p + 2 > unit_end) return;
        uint16_t version; memcpy(&version, p, 2); p += 2;
        if (version < 2 || version > 4) { p = unit_end; continue; } /* only what the backend writes */
        uint32_t header_len; memcpy(&header_len, p, 4); p += 4;
        const unsigned char *program = p + header_len;
        unsigned min_inst = *p++;
        if (version >= 4) p++; /* maximum_operations_per_instruction */
        p++; /* default_is_stmt */
        int line_base = (signed char) *p++;
        unsigned line_range = *p++;
        unsigned opcode_base = *p++;
        const unsigned char *op_lens = p;
        p += opcode_base > 0 ? opcode_base - 1 : 0;
        if (line_range == 0 || program > unit_end) { p = unit_end; continue; }
        /* include_directories */
        size_t dcap = 64; unsigned ndirs = 1;
        const char **dirs = malloc(dcap * sizeof *dirs);
        if (dirs == NULL) return;
        dirs[0] = NULL;
        while (p < program && *p != 0) {
            if (ndirs == dcap) { dcap *= 2; const char **n = realloc(dirs, dcap * sizeof *dirs); if (n == NULL) { free(dirs); return; } dirs = n; }
            dirs[ndirs++] = strdup((const char *) p);
            p += strlen((const char *) p) + 1;
        }
        p++;
        /* file_names */
        size_t fcap = 64; unsigned nfiles = 1;
        const char **fnames = malloc(fcap * sizeof *fnames);
        unsigned *fdirs = malloc(fcap * sizeof *fdirs);
        if (fnames == NULL || fdirs == NULL) { free(dirs); free(fnames); free(fdirs); return; }
        fnames[0] = NULL; fdirs[0] = 0;
        while (p < program && *p != 0) {
            const char *name = strdup((const char *) p);
            p += strlen((const char *) p) + 1;
            unsigned d = (unsigned) nabu_uleb(&p, program);
            nabu_uleb(&p, program); nabu_uleb(&p, program);
            if (nfiles == fcap) {
                fcap *= 2;
                const char **nn = realloc(fnames, fcap * sizeof *fnames);
                unsigned *nd = realloc(fdirs, fcap * sizeof *fdirs);
                if (nn == NULL || nd == NULL) return;
                fnames = nn; fdirs = nd;
            }
            fnames[nfiles] = name; fdirs[nfiles] = d; nfiles++;
        }
        p = program;
        /* the line number program */
        uint64_t addr = 0; unsigned file = 1; int64_t line = 1;
        while (p < unit_end) {
            unsigned op = *p++;
            int emit = 0, end_seq = 0;
            if (op >= opcode_base) {
                unsigned adj = op - opcode_base;
                addr += (uint64_t) (adj / line_range) * min_inst;
                line += line_base + (int) (adj % line_range);
                emit = 1;
            } else if (op == 0) {
                uint64_t len = nabu_uleb(&p, unit_end);
                const unsigned char *next = p + len;
                if (len == 0 || next > unit_end) break;
                unsigned ext = *p++;
                if (ext == 1) { emit = 1; end_seq = 1; }
                else if (ext == 2 && len == 9) { memcpy(&addr, p, 8); }
                p = next;
            } else switch (op) {
                case 1: emit = 1; break;
                case 2: addr += nabu_uleb(&p, unit_end) * min_inst; break;
                case 3: line += nabu_sleb(&p, unit_end); break;
                case 4: file = (unsigned) nabu_uleb(&p, unit_end); break;
                case 8: addr += (uint64_t) ((255 - opcode_base) / line_range) * min_inst; break;
                case 9: { uint16_t a; memcpy(&a, p, 2); p += 2; addr += a; break; }
                default:
                    for (unsigned i = 0; i < op_lens[op - 1]; i++) nabu_uleb(&p, unit_end);
                    break;
            }
            if (emit) {
                uintptr_t a = (uintptr_t) ((intptr_t) addr + delta);
                if (end_seq) {
                    nabu_row_add(a, NULL, NULL, 0);
                    addr = 0; file = 1; line = 1;
                } else if (file > 0 && file < nfiles) {
                    unsigned d = fdirs[file];
                    nabu_row_add(a, d > 0 && d < ndirs ? dirs[d] : NULL, fnames[file], (int) line);
                }
            }
        }
        free(dirs); free(fnames); free(fdirs);
        p = unit_end;
    }
}

static int nabu_row_cmp(const void *a, const void *b) {
    const nabu_line_row *x = (const nabu_line_row *) a, *y = (const nabu_line_row *) b;
    if (x->addr != y->addr) return x->addr < y->addr ? -1 : 1;
    int xe = x->file == NULL, ye = y->file == NULL; /* an end of sequence comes before the next start */
    if (xe != ye) return xe ? -1 : 1;
    return x->seq < y->seq ? -1 : x->seq > y->seq ? 1 : 0;
}

#ifdef _WIN32
/* Reads the COFF symbol table of the running executable: the functions in code sections. */
static void nabu_load_symbols(void) {
    nabu_syms_loaded = 1;
    char path[MAX_PATH];
    if (GetModuleFileNameA(NULL, path, MAX_PATH) == 0) return;
    FILE *f = fopen(path, "rb");
    if (f == NULL) return;
    IMAGE_DOS_HEADER dos;
    IMAGE_NT_HEADERS64 nt;
    IMAGE_SECTION_HEADER *sections = NULL;
    char *strings = NULL;
    unsigned char *table = NULL;
    do {
        if (fread(&dos, sizeof dos, 1, f) != 1 || dos.e_magic != IMAGE_DOS_SIGNATURE) break;
        if (fseek(f, dos.e_lfanew, SEEK_SET) != 0 || fread(&nt, sizeof nt, 1, f) != 1) break;
        DWORD nsyms = nt.FileHeader.NumberOfSymbols;
        DWORD symoff = nt.FileHeader.PointerToSymbolTable;
        WORD nsec = nt.FileHeader.NumberOfSections;
        if (nsyms == 0 || symoff == 0 || nsec == 0) break;
        sections = malloc(nsec * sizeof *sections);
        if (sections == NULL) break;
        long secoff = dos.e_lfanew + 4 + (long) sizeof(IMAGE_FILE_HEADER) + nt.FileHeader.SizeOfOptionalHeader;
        if (fseek(f, secoff, SEEK_SET) != 0 || fread(sections, sizeof *sections, nsec, f) != nsec) break;
        table = malloc((size_t) nsyms * 18);
        if (table == NULL) break;
        if (fseek(f, (long) symoff, SEEK_SET) != 0 || fread(table, 18, nsyms, f) != nsyms) break;
        uint32_t strsize = 0;
        if (fread(&strsize, 4, 1, f) != 1 || strsize < 4) break;
        strings = malloc(strsize);
        if (strings == NULL) break;
        memcpy(strings, &strsize, 4);
        if (strsize > 4 && fread(strings + 4, 1, strsize - 4, f) != strsize - 4) break;
        uintptr_t base = (uintptr_t) GetModuleHandleA(NULL);
        nabu_image_end = base + nt.OptionalHeader.SizeOfImage;
        nabu_syms = malloc((size_t) nsyms * sizeof *nabu_syms);
        nabu_dsyms = malloc((size_t) nsyms * sizeof *nabu_dsyms);
        if (nabu_syms == NULL || nabu_dsyms == NULL) break;
        for (DWORD i = 0; i < nsyms; ) {
            unsigned char *e = table + (size_t) i * 18;
            uint32_t value; int16_t secnum; unsigned char aux = e[17];
            memcpy(&value, e + 8, 4);
            memcpy(&secnum, e + 12, 2);
            if (secnum > 0 && secnum <= nsec) {
                int is_code = (sections[secnum - 1].Characteristics & IMAGE_SCN_CNT_CODE) != 0;
                const char *name = NULL;
                char shortname[9];
                uint32_t zero, off;
                memcpy(&zero, e, 4);
                memcpy(&off, e + 4, 4);
                if (zero == 0) {
                    if (off < strsize) name = strings + off;
                } else {
                    memcpy(shortname, e, 8);
                    shortname[8] = 0;
                    name = shortname;
                }
                if (name != NULL && name[0] != 0 && name[0] != '.') {
                    nabu_sym *slot = is_code ? &nabu_syms[nabu_sym_count++] : &nabu_dsyms[nabu_dsym_count++];
                    slot->addr = base + sections[secnum - 1].VirtualAddress + value;
                    slot->name = strdup(name);
                }
            }
            i += 1 + aux;
        }
        qsort(nabu_syms, nabu_sym_count, sizeof *nabu_syms, nabu_sym_cmp);
        qsort(nabu_dsyms, nabu_dsym_count, sizeof *nabu_dsyms, nabu_dsym_cmp);
        for (WORD si = 0; si < nsec; si++) {
            char secname[9];
            memcpy(secname, sections[si].Name, 8);
            secname[8] = 0;
            const char *sname = secname;
            if (secname[0] == '/') {
                uint32_t off = (uint32_t) atoi(secname + 1);
                if (off < strsize) sname = strings + off;
            }
            if (strcmp(sname, ".debug_line") != 0) continue;
            DWORD size = sections[si].SizeOfRawData;
            if (sections[si].Misc.VirtualSize != 0 && sections[si].Misc.VirtualSize < size) size = sections[si].Misc.VirtualSize;
            unsigned char *buf = malloc(size);
            if (buf != NULL && fseek(f, (long) sections[si].PointerToRawData, SEEK_SET) == 0
                    && fread(buf, 1, size, f) == size) {
                nabu_parse_lines(buf, size, (intptr_t) (base - (uintptr_t) nt.OptionalHeader.ImageBase));
                qsort(nabu_rows, nabu_row_count, sizeof *nabu_rows, nabu_row_cmp);
            }
            free(buf);
        }
    } while (0);
    free(sections);
    free(table);
    free(strings);
    fclose(f);
}

static volatile int nabu_syms_ready = 0;

/* The symbol tables are read once, by the first thread that needs them; the others wait for it. */
static void nabu_ensure_symbols(void) {
    if (__atomic_load_n(&nabu_syms_ready, __ATOMIC_ACQUIRE)) return;
    nabu_rt_lock();
    if (!nabu_syms_loaded) {
        nabu_load_symbols();
        __atomic_store_n(&nabu_syms_ready, 1, __ATOMIC_RELEASE);
    }
    nabu_rt_unlock();
}

/* The address of a global by its symbol name (for example "java.util.concurrent.ForkJoinPool_poolIds"), or NULL. */
void *nabu_data_symbol(const char *name) {
    nabu_ensure_symbols();
    size_t lo = 0, hi = nabu_dsym_count;
    while (lo < hi) {
        size_t mid = lo + (hi - lo) / 2;
        int c = strcmp(nabu_dsyms[mid].name, name);
        if (c == 0) return (void *) nabu_dsyms[mid].addr;
        if (c < 0) lo = mid + 1; else hi = mid;
    }
    return NULL;
}

/* The symbol of the function that contains the address, or NULL. */
const char *nabu_frame_name(uintptr_t addr) {
    nabu_ensure_symbols();
    if (addr >= nabu_image_end) return NULL; /* a system DLL */
    size_t lo = 0, hi = nabu_sym_count;
    while (lo < hi) {
        size_t mid = lo + (hi - lo) / 2;
        if (nabu_syms[mid].addr <= addr) lo = mid + 1; else hi = mid;
    }
    return lo == 0 ? NULL : nabu_syms[lo - 1].name;
}

/* The line of the address: its row's function symbol, file and line; 0 when there is none. */
int nabu_frame_line(uintptr_t addr, const char **scope, const char **file) {
    nabu_ensure_symbols();
    size_t lo = 0, hi = nabu_row_count;
    while (lo < hi) {
        size_t mid = lo + (hi - lo) / 2;
        if (nabu_rows[mid].addr <= addr) lo = mid + 1; else hi = mid;
    }
    if (lo == 0) return 0;
    const nabu_line_row *r = &nabu_rows[lo - 1];
    if (r->file == NULL) return 0; /* after the end of a sequence */
    *scope = r->dir;
    *file = r->file;
    return r->line;
}
#else
#include <dlfcn.h>
const char *nabu_frame_name(uintptr_t addr) {
    Dl_info info;
    return dladdr((void *) addr, &info) != 0 ? info.dli_sname : NULL;
}
/* A data symbol is found through the dynamic symbol table, which the link command fills with -rdynamic (this is
   also what gives dladdr above the names of the functions). */
void *nabu_data_symbol(const char *name) {
    return dlsym(RTLD_DEFAULT, name);
}

#if defined(__linux__)
#include <elf.h>
static int nabu_lines_loaded = 0;
static volatile int nabu_lines_ready = 0;

/* Reads the .debug_line section of the running ELF executable (the backend's line tables, see above). The
   addresses in it are link-time addresses: a position independent executable is moved by its load address. */
static void nabu_load_lines(void) {
    nabu_lines_loaded = 1;
    FILE *f = fopen("/proc/self/exe", "rb");
    if (f == NULL) return;
    Elf64_Ehdr eh;
    Elf64_Shdr *sh = NULL;
    char *names = NULL;
    do {
        if (fread(&eh, sizeof eh, 1, f) != 1 || memcmp(eh.e_ident, ELFMAG, SELFMAG) != 0
                || eh.e_ident[EI_CLASS] != ELFCLASS64) break;
        if (eh.e_shnum == 0 || eh.e_shentsize != sizeof *sh || eh.e_shstrndx >= eh.e_shnum) break;
        sh = malloc(eh.e_shnum * sizeof *sh);
        if (sh == NULL || fseek(f, (long) eh.e_shoff, SEEK_SET) != 0
                || fread(sh, sizeof *sh, eh.e_shnum, f) != eh.e_shnum) break;
        const Elf64_Shdr *str = &sh[eh.e_shstrndx];
        names = malloc(str->sh_size + 1);
        if (names == NULL || fseek(f, (long) str->sh_offset, SEEK_SET) != 0
                || fread(names, 1, str->sh_size, f) != str->sh_size) break;
        names[str->sh_size] = 0;
        Dl_info info;
        intptr_t delta = 0;
        if (eh.e_type == ET_DYN && dladdr((void *) nabu_load_lines, &info) != 0) delta = (intptr_t) info.dli_fbase;
        for (unsigned i = 0; i < eh.e_shnum; i++) {
            if (sh[i].sh_name >= str->sh_size || strcmp(names + sh[i].sh_name, ".debug_line") != 0) continue;
            unsigned char *buf = malloc(sh[i].sh_size);
            if (buf != NULL && fseek(f, (long) sh[i].sh_offset, SEEK_SET) == 0
                    && fread(buf, 1, sh[i].sh_size, f) == sh[i].sh_size) {
                nabu_parse_lines(buf, sh[i].sh_size, delta);
                qsort(nabu_rows, nabu_row_count, sizeof *nabu_rows, nabu_row_cmp);
            }
            free(buf);
        }
    } while (0);
    free(sh);
    free(names);
    fclose(f);
}
#else
static int nabu_lines_loaded = 1; /* no line tables read on this platform (macOS: Mach-O) */
static volatile int nabu_lines_ready = 0;
static void nabu_load_lines(void) {
}
#endif

/* The line of the address: its row's function symbol, file and line; 0 when there is none. */
int nabu_frame_line(uintptr_t addr, const char **scope, const char **file) {
    if (!__atomic_load_n(&nabu_lines_ready, __ATOMIC_ACQUIRE)) {
        nabu_rt_lock();
        if (!nabu_lines_loaded) {
            nabu_load_lines();
            __atomic_store_n(&nabu_lines_ready, 1, __ATOMIC_RELEASE);
        }
        nabu_rt_unlock();
    }
    size_t lo = 0, hi = nabu_row_count;
    while (lo < hi) {
        size_t mid = lo + (hi - lo) / 2;
        if (nabu_rows[mid].addr <= addr) lo = mid + 1; else hi = mid;
    }
    if (lo == 0) return 0;
    const nabu_line_row *r = &nabu_rows[lo - 1];
    if (r->file == NULL) return 0; /* after the end of a sequence */
    *scope = r->dir;
    *file = r->file;
    return r->line;
}
#endif

/* Throwable.getMessage van het programma (zie nabu_set_throwable_message_fn);
 * alleen voor de melding bij een onbehandelde exception. */
static void *(*nabu_throwable_message_fn)(void *) = NULL;

void nabu_set_throwable_message_fn(void *fn) {
    nabu_throwable_message_fn = (void *(*)(void *))fn;
}

/* The shutdown hooks of the program (ShutdownSupport.runHooks), run once when the process exits. */
static void (*nabu_shutdown_fn)(void) = NULL;

static void nabu_run_shutdown(void) {
    void (*fn)(void) = nabu_shutdown_fn;
    nabu_shutdown_fn = NULL;
    if (fn != NULL) fn();
}

void nabu_set_shutdown_fn(void *fn) {
    static int registered = 0;
    nabu_shutdown_fn = (void (*)(void))fn;
    if (!registered) {
        registered = 1;
        atexit(nabu_run_shutdown);
    }
}

/* Throwable.printStackTrace() van het programma; voor een onbehandelde exception. */
static void (*nabu_throwable_print_fn)(void *) = NULL;

void nabu_set_throwable_print_fn(void *fn) {
    nabu_throwable_print_fn = (void (*)(void *))fn;
}

void nabu_throw(void *exception) {
    nabu_unwind_exception *exc = malloc(sizeof(nabu_unwind_exception));
    if (exc == NULL) {
        fprintf(stderr, "nabu: cannot allocate memory for exception\n");
        abort();
    }

    memset(&exc->unwind_header, 0, sizeof(struct _Unwind_Exception));
    exc->unwind_header.exception_class = NABU_EXC_CLASS;
    exc->unwind_header.exception_cleanup = nabu_exception_cleanup;
    exc->nabu_object = exception;

    _Unwind_Reason_Code result = _Unwind_RaiseException(&exc->unwind_header);

    /* Als we hier komen is er geen handler gevonden */
    (void)result;
    if (exception != NULL && nabu_throwable_print_fn != NULL && !nabu_jcl_is_object(exception)) {
        /* Like the JVM: Exception in thread "main" <class>: <message> and the trace. */
        fflush(stdout);
        fprintf(stderr, "Exception in thread \"main\" ");
        fflush(stderr);
        nabu_throwable_print_fn(exception);
        fflush(stderr);
        exit(1);
    }
    fprintf(stderr, "nabu: unhandled exception\n");
    if (exception != NULL) {
        nabu_type_info *ti = ((nabu_object *)exception)->type;
        if (ti != NULL && ti->name != NULL) {
            fprintf(stderr, "nabu: exception class: %s\n", ti->name);
            /* Alleen voor gecompileerde Throwables: een library-exception
             * (jcl-object) heeft de velden van Throwable niet. */
            if (nabu_throwable_message_fn != NULL && !nabu_jcl_is_object(exception)) {
                void *msg = nabu_throwable_message_fn(exception);
                if (msg != NULL) {
                    fprintf(stderr, "nabu: message: %s\n", nabu_cstr(msg));
                }
            }
        } else {
            fprintf(stderr, "nabu: exception class: <unknown, type=%p>\n", (void *)ti);
        }
    } else {
        fprintf(stderr, "nabu: exception object is NULL\n");
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
    /* unwind_exc is the Nabu exception object itself, not an _Unwind_Exception: the backend
     * (FunctionEmitter.emitLandingPadChain) already took it out of the wrapper with nabu_catch()
     * before it calls nabu_resume. Handing it to _Unwind_Resume made the unwinder read the
     * object's fields as unwinder state: private_1 (offset 16, the itable of a class that
     * implements an interface) is the stop function of a forced unwind, so a rethrown
     * RuntimeException jumped into read-only data.
     *
     * A fresh raise from the landing pad starts a new search above this frame instead. The
     * landing pad lies outside every call-site range of the LSDA (it is not a protected region),
     * so the personality continues the unwind and the surrounding handlers are found. On Windows
     * this is also needed: once the SEH handler of a frame has been established, _Unwind_Resume
     * returns to the same landing pad (an endless loop at 100% CPU). Without a handler,
     * nabu_throw returns and the program ends cleanly with "unhandled exception".
     */
    nabu_throw(unwind_exc);
    abort(); /* nabu_throw only returns when there is no handler */
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

#ifndef _WIN32
/* Linux and macOS: the unwinder calls the personality directly (no SEH wrapper in between). The name is the one
   the backend puts on every function with a landing pad. */
_Unwind_Reason_Code
nabu_seh_personality(int version, _Unwind_Action actions, _Unwind_Exception_Class exception_class,
                     struct _Unwind_Exception *ue_header, struct _Unwind_Context *context) {
    return nabu_itanium_personality(version, actions, exception_class, ue_header, context);
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
#elif defined(__linux__)
    /* Same idea as on Windows: the first word of a compiled object points to its type info, which is part of
       the executable's own data (between __executable_start and end). */
    extern const char __executable_start;
    extern const char end;
    if (p == NULL) return NULL;
    const char *t = ((const char *const *) p)[0];
    if (t < &__executable_start || t >= &end) return NULL;
    const char *name = ((const nabu_type_info *) t)->name;
    if (name < &__executable_start || name >= &end) return NULL;
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

/* Whether the internal class name `actual` is `wanted` or a subtype of it (compiled classes only). */
static int nabu_type_assignable(const char *actual, const char *wanted) {
    if (strcmp(actual, wanted) == 0 || strcmp(wanted, "java/lang/Object") == 0) return 1;
    for (const nabu_type_info *ti = nabu_compiled_type_for(actual); ti != NULL; ti = ti->super_type) {
        if (strcmp(ti->name, wanted) == 0) return 1;
        for (int i = 0; i < ti->interface_count; i++) {
            if (strcmp(ti->interfaces[i], wanted) == 0) return 1;
        }
    }
    return 0;
}

int nabu_instanceof_array(void *obj, const char *wanted);
extern const char *nabu_array_type_name(int32_t id);

/* Throws ClassCastException for an object of class `actual` cast to `wanted` (internal names). */
static void nabu_throw_cce(const char *actual, const char *wanted) {
    char message[512];
    snprintf(message, sizeof message, "class %s cannot be cast to class %s", actual, wanted);
    for (char *p = message; *p; p++) if (*p == '/') *p = '.';
    void *exception = nabu_new_exception("java/lang/ClassCastException", message);
    if (exception == NULL) {
        exception = calloc(1, sizeof(nabu_object) + 2 * sizeof(void *));
        nabu_init_object_ex(exception, "java/lang/ClassCastException", "java/lang/RuntimeException");
    }
    nabu_throw(exception);
    fprintf(stderr, "nabu: unhandled ClassCastException\n");
    abort();
}

/* A checked cast to a class: obj if it is an instance of the class (or null), else ClassCastException. Only an
 * object of a compiled class can fail: an object the C runtime made, or a class the program does not know, passes. */
void *nabu_checkcast_class(void *obj, nabu_type_info *wanted) {
    if (obj == NULL || wanted == NULL) return obj;
    const char *actual = nabu_compiled_type_name(obj);
    if (actual == NULL) return obj;
    for (const nabu_type_info *t = ((nabu_object *) obj)->type; t != NULL; t = t->super_type) {
        if (t == wanted) return obj;
    }
    if (nabu_instanceof(obj, wanted->name)) return obj;
    nabu_throw_cce(actual, wanted->name);
}

/* A checked cast to an interface or an array type, by name ("java/lang/Runnable", "[Ljava/lang/String;"). It
 * only fails for what the runtime can judge: an object of a compiled class, or an array of a known type. */
void *nabu_checkcast_name(void *obj, const char *wanted) {
    if (obj == NULL || wanted == NULL) return obj;
    const char *actual = nabu_compiled_type_name(obj);
    if (wanted[0] == '[') {
        if (actual != NULL) nabu_throw_cce(actual, wanted);
        const int32_t *w = (const int32_t *) obj;
        int32_t es = w[1] & 0xff;
        if (w[0] < 0 || !(es == 1 || es == 2 || es == 4 || es == 8)) return obj;
        const char *array = nabu_array_type_name((int32_t) ((uint32_t) w[1] >> 8));
        if (array == NULL || nabu_instanceof_array(obj, wanted)) return obj;
        nabu_throw_cce(array, wanted);
    }
    if (actual == NULL || nabu_instanceof(obj, wanted)) return obj;
    nabu_throw_cce(actual, wanted);
    return obj;
}

/* `obj instanceof T[]`: `wanted` is the JVM name of the array type ("[B", "[Ljava/lang/String;"). An array has
 * no object header; it is recognized by its length, element size and the array type id the backend writes. */
int nabu_instanceof_array(void *obj, const char *wanted) {
    if (obj == NULL) return 0;
    const int32_t *w = (const int32_t *) obj;
    int32_t es = w[1] & 0xff;
    if (w[0] < 0 || !(es == 1 || es == 2 || es == 4 || es == 8)) return 0;
    const char *actual = nabu_array_type_name((int32_t) ((uint32_t) w[1] >> 8));
    if (actual == NULL) return 0; /* an object, or an array of unknown type */
    if (strcmp(actual, wanted) == 0) return 1;
    if (actual[0] != '[' || wanted[0] != '[') return 0;
    /* Reference arrays are covariant: String[] is an Object[] and a CharSequence[]. */
    const char *a = actual + 1, *b = wanted + 1;
    while (*a == '[' && *b == '[') { a++; b++; }
    if (*b == '[') return 0;
    if (*a != 'L' && *a != '[') return 0;       /* a primitive array only matches itself */
    if (*b != 'L') return 0;
    if (*a == '[') return strcmp(b, "Ljava/lang/Object;") == 0;
    char actual_name[256], wanted_name[256];
    size_t la = strlen(a), lb = strlen(b);
    if (la < 3 || lb < 3 || la >= sizeof actual_name || lb >= sizeof wanted_name) return 0;
    memcpy(actual_name, a + 1, la - 2); actual_name[la - 2] = 0;
    memcpy(wanted_name, b + 1, lb - 2); wanted_name[lb - 2] = 0;
    return nabu_type_assignable(actual_name, wanted_name);
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

/* Array classes: the upper 24 bits of the element-size word index
 * nabu_array_type_names (emitted by the backend, the types the program
 * allocates) and, from nabu_array_type_count on, the types the runtime met
 * later (annotation arrays, Array.newInstance, ...). */
extern const char *const nabu_array_type_names[];
extern const int32_t nabu_array_type_count;

#define NABU_MAX_ARRAY_TYPES (1 << 24)

static char **nabu_dyn_array_names = NULL;
static int32_t nabu_dyn_array_count = 0;
static int32_t nabu_dyn_array_cap = 0;
static volatile char nabu_array_lock = 0;

static void nabu_array_lock_acquire(void) { while (__sync_lock_test_and_set(&nabu_array_lock, 1)) { } }
static void nabu_array_lock_release(void) { __sync_lock_release(&nabu_array_lock); }

/* The JVM name of an array type id, or NULL when the id is unknown. */
const char *nabu_array_type_name(int32_t id) {
    if (id <= 0) return NULL;
    if (id < nabu_array_type_count) return nabu_array_type_names[id];
    int32_t k = id - nabu_array_type_count;
    const char *name = NULL;
    nabu_array_lock_acquire();
    if (k < nabu_dyn_array_count) name = nabu_dyn_array_names[k];
    nabu_array_lock_release();
    return name;
}

/* The id of an array type by its JVM name; a name the program does not
 * allocate gets a new id, so every array the runtime makes has its class. */
int32_t nabu_array_type_id(const char *name) {
    for (int32_t i = 1; i < nabu_array_type_count; i++) {
        if (nabu_array_type_names[i] != NULL && strcmp(nabu_array_type_names[i], name) == 0) return i;
    }
    int32_t id = 0;
    nabu_array_lock_acquire();
    for (int32_t k = 0; k < nabu_dyn_array_count; k++) {
        if (strcmp(nabu_dyn_array_names[k], name) == 0) { id = nabu_array_type_count + k; break; }
    }
    if (id == 0 && nabu_array_type_count + nabu_dyn_array_count < NABU_MAX_ARRAY_TYPES) {
        if (nabu_dyn_array_count == nabu_dyn_array_cap) {
            int32_t cap = nabu_dyn_array_cap == 0 ? 16 : nabu_dyn_array_cap * 2;
            char **grown = realloc(nabu_dyn_array_names, (size_t) cap * sizeof *grown);
            if (grown != NULL) { nabu_dyn_array_names = grown; nabu_dyn_array_cap = cap; }
        }
        char *copy = nabu_dyn_array_count < nabu_dyn_array_cap ? strdup(name) : NULL;
        if (copy != NULL) {
            nabu_dyn_array_names[nabu_dyn_array_count] = copy;
            id = nabu_array_type_count + nabu_dyn_array_count;
            nabu_dyn_array_count++;
        }
    }
    nabu_array_lock_release();
    return id;
}

static int32_t nabu_byte_array_id = -1;

void nabu_array_tag(void *arr, const char *name) {
    if (arr == NULL) return;
    int32_t es = ((int32_t *) arr)[1] & 0xff;
    ((int32_t *) arr)[1] = es | (nabu_array_type_id(name) << 8);
}

static void *nabu_str_mem(size_t n) {
    void *p = NABU_STR_MEM(n);
    if (p == NULL) {
        fprintf(stderr, "nabu: cannot allocate memory for a string\n");
        abort();
    }
    return p;
}

/* Een virtuele/interface-aanroep op null (zie InstructionEmitter.
 * emitReceiverNullCheck): gooit java.lang.NullPointerException. `site`
 * ("callee in functie") wordt gemeld als NABU_TRACE_NPE gezet is. */
/* Exceptions the runtime throws itself, as real compiled objects: the
 * backend emits their type, tables, size and (String) constructor
 * (LLVMModuleEmitter.emitExceptionTable). A header-only shell made compiled
 * code that called getMessage() on a caught exception read past its end. */
typedef struct nabu_exception_entry {
    const char *name; void *type; void *vtable; void *itable; int64_t size; void (*init)(void *, void *);
} nabu_exception_entry;
extern const nabu_exception_entry nabu_exception_table[];
extern const int32_t nabu_exception_count;

void *nabu_new_exception(const char *internal_name, const char *message) {
    for (int32_t i = 0; i < nabu_exception_count; i++) {
        const nabu_exception_entry *e = &nabu_exception_table[i];
        if (strcmp(e->name, internal_name) == 0) {
            nabu_object *obj = (nabu_object *) nabu_str_mem((size_t) e->size);
            obj->type = e->type;
            obj->vtable = e->vtable;
            obj->itable = e->itable;
            e->init(obj, message != NULL ? nabu_jstring_new(message) : NULL);
            return obj;
        }
    }
    return NULL;
}

void nabu_throw_npe(const char *site) {
    static int trace = -1;
    if (trace < 0) trace = getenv("NABU_TRACE_NPE") != NULL;
    if (trace) fprintf(stderr, "nabu: NullPointerException: %s\n", site != NULL ? site : "?");
    void *obj = nabu_new_exception("java/lang/NullPointerException", NULL);
    if (obj == NULL) {
        obj = nabu_str_mem(sizeof(nabu_object) + 2 * sizeof(void *));
        nabu_init_object_ex(obj, "java/lang/NullPointerException", "java/lang/RuntimeException");
    }
    nabu_throw(obj);
    fprintf(stderr, "nabu: unhandled NullPointerException\n");
    abort();
}

/* An integer division or remainder by zero. */
void nabu_throw_arith(void) {
    void *obj = nabu_new_exception("java/lang/ArithmeticException", "/ by zero");
    if (obj == NULL) {
        obj = nabu_str_mem(sizeof(nabu_object) + 2 * sizeof(void *));
        nabu_init_object_ex(obj, "java/lang/ArithmeticException", "java/lang/RuntimeException");
    }
    nabu_throw(obj);
    fprintf(stderr, "nabu: unhandled ArithmeticException\n");
    abort();
}

/* An array index outside 0 .. length-1. */
void nabu_throw_aioobe(int32_t index, int32_t length) {
    char message[96];
    snprintf(message, sizeof message, "Index %d out of bounds for length %d", (int) index, (int) length);
    void *obj = nabu_new_exception("java/lang/ArrayIndexOutOfBoundsException", message);
    if (obj == NULL) {
        obj = nabu_str_mem(sizeof(nabu_object) + 2 * sizeof(void *));
        nabu_init_object_ex(obj, "java/lang/ArrayIndexOutOfBoundsException", "java/lang/RuntimeException");
    }
    nabu_throw(obj);
    fprintf(stderr, "nabu: unhandled ArrayIndexOutOfBoundsException\n");
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
    if (nabu_byte_array_id < 0) nabu_byte_array_id = nabu_array_type_id("[B");
    ((int32_t *) arr)[1] = 1 | (nabu_byte_array_id << 8); /* byte[] */
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
    /* Java: een null-operand in een string-concat wordt "null". */
    a = a == NULL ? "null" : nabu_cstr(a);
    b = b == NULL ? "null" : nabu_cstr(b);
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
        fprintf(stderr, "nabu: cannot allocate memory for concat\n");
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
        fprintf(stderr, "nabu: cannot allocate memory for int->string\n");
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
        fprintf(stderr, "nabu: cannot allocate memory for long->string\n");
        abort();
    }
    memcpy(out, buf, (size_t) n + 1);
    return nabu_string_meta.type != NULL ? (char *) nabu_jstring_new(out) : out;
}

/* Java-semantiek van Double.toString: altijd een decimaalteken of exponent, en
 * NaN/Infinity met een hoofdletter. %g levert "1" voor 1.0, dus daarachter een
 * ".0" toevoegen. */
/* Double.toString / Float.toString: the shortest digits that read back as the same value, in plain notation for
 * 1e-3 <= |v| < 1e7 and as computerized scientific notation ("1.0E10") otherwise. */
static void nabu_java_fp(char *out, size_t cap, double v, int is_float) {
    if (v != v) { snprintf(out, cap, "NaN"); return; }
    if (v > 1.7976931348623157e308) { snprintf(out, cap, "Infinity"); return; }
    if (v < -1.7976931348623157e308) { snprintf(out, cap, "-Infinity"); return; }
    if (v == 0) { snprintf(out, cap, signbit(v) ? "-0.0" : "0.0"); return; }
    char sci[48];
    for (int prec = 0; prec <= 17; prec++) {
        snprintf(sci, sizeof sci, "%.*e", prec, v);
        if (is_float ? strtof(sci, NULL) == (float) v : strtod(sci, NULL) == v) break;
    }
    /* sci is [-]d[.ddd]e[+-]xx */
    const char *p = sci;
    int negative = 0;
    if (*p == '-') { negative = 1; p++; }
    char digits[24];
    int nd = 0;
    while (*p && *p != 'e') {
        if (*p != '.' && nd < 20) digits[nd++] = *p;
        p++;
    }
    int exp10 = (*p == 'e') ? atoi(p + 1) : 0;
    while (nd > 1 && digits[nd - 1] == '0') nd--;
    digits[nd] = 0;
    char *o = out;
    size_t left = cap;
    #define NABU_PUT(c) do { if (left > 1) { *o++ = (c); left--; } } while (0)
    if (negative) NABU_PUT('-');
    if (exp10 >= -3 && exp10 < 7) {
        if (exp10 >= 0) {
            for (int k = 0; k <= exp10; k++) NABU_PUT(k < nd ? digits[k] : '0');
            NABU_PUT('.');
            if (nd > exp10 + 1) { for (int k = exp10 + 1; k < nd; k++) NABU_PUT(digits[k]); }
            else NABU_PUT('0');
        } else {
            NABU_PUT('0'); NABU_PUT('.');
            for (int k = 0; k < -exp10 - 1; k++) NABU_PUT('0');
            for (int k = 0; k < nd; k++) NABU_PUT(digits[k]);
        }
    } else {
        NABU_PUT(digits[0]); NABU_PUT('.');
        if (nd > 1) { for (int k = 1; k < nd; k++) NABU_PUT(digits[k]); }
        else NABU_PUT('0');
        NABU_PUT('E');
        char e[16];
        snprintf(e, sizeof e, "%d", exp10);
        for (const char *q = e; *q; q++) NABU_PUT(*q);
    }
    #undef NABU_PUT
    *o = 0;
}

static char *nabu_fp_result(const char *buf) {
    size_t len = strlen(buf);
    char *out = malloc(len + 1);
    if (out == NULL) {
        fprintf(stderr, "nabu: cannot allocate memory for double->string\n");
        abort();
    }
    memcpy(out, buf, len + 1);
    return nabu_string_meta.type != NULL ? (char *) nabu_jstring_new(out) : out;
}

char *nabu_double_to_string(double value) {
    char buf[48];
    nabu_java_fp(buf, sizeof buf, value, 0);
    return nabu_fp_result(buf);
}

char *nabu_float_to_string(float value) {
    char buf[48];
    nabu_java_fp(buf, sizeof buf, (double) value, 1);
    return nabu_fp_result(buf);
}

char *nabu_bool_to_string(int32_t value) {
    const char *text = value ? "true" : "false";
    size_t len = strlen(text);
    char *out = malloc(len + 1);
    if (out == NULL) {
        fprintf(stderr, "nabu: cannot allocate memory for bool->string\n");
        abort();
    }
    memcpy(out, text, len + 1);
    return nabu_string_meta.type != NULL ? (char *) nabu_jstring_new(out) : out;
}

/* Gecompileerde wrapper-valueOf's per soort (zie nabu_set_box_fn). */
static void *nabu_box_fns[8];

void nabu_set_box_fn(int32_t kind, void *fn) {
    if (kind >= 0 && kind < 8) nabu_box_fns[kind] = fn;
}

void *nabu_box_fn(int32_t kind) {
    return kind >= 0 && kind < 8 ? nabu_box_fns[kind] : NULL;
}

/* Vtable-slot van Object.toString (zie nabu_set_object_tostring_slot). */
static int32_t nabu_tostring_slot = -1;

void nabu_set_object_tostring_slot(int32_t slot) {
    nabu_tostring_slot = slot;
}

/* Java-semantiek van String.valueOf(Object) voor een object-operand in een
 * string-concat ("" + set): "null" of de virtuele toString() van het object.
 * Een String (of een kale C-string uit de runtime) gaat ongewijzigd door. */
char *nabu_object_to_string(void *obj) {
    if (obj == NULL) {
        return nabu_string_meta.type != NULL ? (char *) nabu_jstring_new("null") : (char *) "null";
    }
    if (nabu_is_jstring(obj) || nabu_tostring_slot < 0) {
        return (char *) obj;
    }
    void **vtable = (void **) ((nabu_object *) obj)->vtable;
    if (vtable == NULL || vtable[nabu_tostring_slot] == NULL) {
        return (char *) obj;
    }
    void *(*to_string)(void *) = (void *(*)(void *)) vtable[nabu_tostring_slot];
    void *result = to_string(obj);
    return result != NULL ? (char *) result : nabu_object_to_string(NULL);
}

/*
 * Threads. Compiled code and the runtime are not thread safe, so threads run one at a time: a global lock (the
 * GIL) is held by the running thread and handed over only where a thread blocks (sleep, join, wait, park, a
 * contended monitor, yield) or ends. A thread that spins on a variable without calling one of those never gives
 * the others a turn. The lock is a ticket lock, so waiting threads run in the order they asked. Until the first
 * Thread.start nothing is locked and these functions do next to nothing.
 */
static pthread_mutex_t nabu_gil_mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t nabu_gil_turn = PTHREAD_COND_INITIALIZER;
static pthread_cond_t nabu_gil_event = PTHREAD_COND_INITIALIZER;
static unsigned long nabu_gil_next = 0;
static unsigned long nabu_gil_serving = 0;
static unsigned long nabu_gil_events = 0;
volatile int nabu_threads_active = 0;
volatile int nabu_parallel = 0;
volatile int nabu_monitor_waiters = 0;
/* Where the running thread tells what it waits for, see nabu_wait_mark. */
static __thread volatile int *nabu_wait_slot = NULL;

void nabu_set_wait_slot(volatile int *slot) { nabu_wait_slot = slot; }
void nabu_wait_mark(int state) { if (nabu_wait_slot != NULL) *nabu_wait_slot = state; }

/* The id of the running thread, in the header of the object whose monitor it holds. The main thread is 1. */
static __thread int nabu_thread_id = 1;

int nabu_current_thread_id(void) { return nabu_thread_id; }
void nabu_set_current_thread_id(int id) { nabu_thread_id = id; }

int nabu_clinit_begin(volatile int32_t *state) {
    const int32_t marker = (nabu_thread_id << 1) | 1;
    struct timespec began;
    clock_gettime(CLOCK_REALTIME, &began);
    nabu_waiter *waiter = NULL;
    int result;
    for (;;) {
        const unsigned long ticket = waiter != NULL ? nabu_waiter_ticket(waiter) : 0;
        int32_t seen = __atomic_load_n(state, __ATOMIC_ACQUIRE);
        if (seen == 2 || seen == marker) { result = 0; break; }
        if (seen == 0) {
            int32_t expected = 0;
            if (__atomic_compare_exchange_n(state, &expected, marker, 0, __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) {
                result = 1;
                break;
            }
            continue;
        }
        /* another thread is initializing the class: wait for it. An initializer that threw never finishes, so
         * after a long wait carry on as the old code did (the class stays half initialized). */
        struct timespec now;
        clock_gettime(CLOCK_REALTIME, &now);
        if ((now.tv_sec - began.tv_sec) * 1000L + (now.tv_nsec - began.tv_nsec) / 1000000L > 10000) { result = 0; break; }
        if (waiter == NULL) {
            /* in the queue of the class before the next look at its state */
            waiter = nabu_waiter_self();
            nabu_keyq_add((const void *) state, waiter);
            continue;
        }
        if (nabu_threads_active) {
            nabu_waiter_wait(waiter, ticket, 100);
        }
    }
    if (waiter != NULL) nabu_keyq_remove((const void *) state, waiter);
    return result;
}

void nabu_clinit_end(volatile int32_t *state) {
    __atomic_store_n(state, 2, __ATOMIC_SEQ_CST);
    nabu_keyq_wake((const void *) state);
}

/* Waits for the turn, with the mutex held. */
static void nabu_gil_take_turn(void) {
    unsigned long ticket = nabu_gil_next++;
    while (nabu_gil_serving != ticket) {
        pthread_cond_wait(&nabu_gil_turn, &nabu_gil_mutex);
    }
}

void nabu_gil_acquire(void) {
    if (nabu_parallel) return;
    pthread_mutex_lock(&nabu_gil_mutex);
    nabu_gil_take_turn();
    pthread_mutex_unlock(&nabu_gil_mutex);
}

void nabu_gil_release(void) {
    if (nabu_parallel) return;
    pthread_mutex_lock(&nabu_gil_mutex);
    nabu_gil_serving++;
    pthread_cond_broadcast(&nabu_gil_turn);
    pthread_mutex_unlock(&nabu_gil_mutex);
}

/* Lets the other threads run, and waits for the next turn. */
void nabu_gil_yield(void) {
    if (!nabu_threads_active) return;
    if (nabu_parallel) { sched_yield(); return; }
    pthread_mutex_lock(&nabu_gil_mutex);
    nabu_gil_serving++;
    pthread_cond_broadcast(&nabu_gil_turn);
    nabu_gil_take_turn();
    pthread_mutex_unlock(&nabu_gil_mutex);
}

/* Wakes the threads in nabu_gil_wait: something they may be waiting for has changed. */
void nabu_gil_signal(void) {
    /* in parallel mode every wake-up is aimed at a thread (nabu_waiter_wake, nabu_keyq_wake) */
    if (!nabu_threads_active || nabu_parallel) return;
    pthread_mutex_lock(&nabu_gil_mutex);
    nabu_gil_events++;
    pthread_cond_broadcast(&nabu_gil_event);
    pthread_mutex_unlock(&nabu_gil_mutex);
}

unsigned long nabu_gil_ticket(void) {
    return __atomic_load_n(&nabu_gil_events, __ATOMIC_SEQ_CST);
}

struct nabu_waiter {
    pthread_mutex_t mutex;
    pthread_cond_t wake;
    unsigned long seq;
    int sleeping;
};

static __thread nabu_waiter *nabu_self_waiter = NULL;

/* Never freed: another thread may still try to wake the waiter of a thread that has ended. */
nabu_waiter *nabu_waiter_self(void) {
    if (nabu_self_waiter == NULL) {
        nabu_waiter *waiter = (nabu_waiter *) calloc(1, sizeof *waiter);
        pthread_mutex_init(&waiter->mutex, NULL);
        pthread_cond_init(&waiter->wake, NULL);
        nabu_self_waiter = waiter;
    }
    return nabu_self_waiter;
}

static nabu_wake_hook_fn nabu_wake_hook = NULL;

void nabu_set_wake_hook(nabu_wake_hook_fn hook) { nabu_wake_hook = hook; }

unsigned long nabu_waiter_ticket(nabu_waiter *waiter) {
    return nabu_parallel ? __atomic_load_n(&waiter->seq, __ATOMIC_SEQ_CST) : nabu_gil_ticket();
}

/* A wait of at most this long at a time as a safety net; every wake-up is targeted, so a thread that waits does not wake for nothing. */
#define NABU_WAITER_SPAN_MS 1000

int nabu_waiter_wait(nabu_waiter *waiter, unsigned long ticket, int64_t timeout_ms) {
    if (!nabu_threads_active) return 1;
    if (!nabu_parallel) {
        const int timed_out = nabu_gil_wait(timeout_ms);
        if (nabu_wake_hook != NULL) nabu_wake_hook();
        return timed_out;
    }
    if (timeout_ms == 0) return 1;
    const int bounded = timeout_ms > 0 && timeout_ms <= NABU_WAITER_SPAN_MS;
    const int64_t span = bounded ? timeout_ms : NABU_WAITER_SPAN_MS;
    struct timespec deadline;
    clock_gettime(CLOCK_REALTIME, &deadline);
    deadline.tv_sec += (time_t)(span / 1000);
    deadline.tv_nsec += (long)(span % 1000) * 1000000L;
    if (deadline.tv_nsec >= 1000000000L) { deadline.tv_sec++; deadline.tv_nsec -= 1000000000L; }
    int timed_out = 0;
    pthread_mutex_lock(&waiter->mutex);
    /* sleeping first, then the look at the counter: the waker raises the counter first and then looks at sleeping */
    __atomic_store_n(&waiter->sleeping, 1, __ATOMIC_SEQ_CST);
    while (__atomic_load_n(&waiter->seq, __ATOMIC_SEQ_CST) == ticket && !timed_out) {
        if (pthread_cond_timedwait(&waiter->wake, &waiter->mutex, &deadline) != 0) timed_out = 1;
    }
    __atomic_store_n(&waiter->sleeping, 0, __ATOMIC_SEQ_CST);
    pthread_mutex_unlock(&waiter->mutex);
    if (nabu_wake_hook != NULL) nabu_wake_hook();
    return timed_out && bounded;
}

void nabu_waiter_wake(nabu_waiter *waiter) {
    if (!nabu_parallel) {
        nabu_gil_signal();
        return;
    }
    if (waiter == NULL) return;
    __atomic_add_fetch(&waiter->seq, 1, __ATOMIC_SEQ_CST);
    if (__atomic_load_n(&waiter->sleeping, __ATOMIC_SEQ_CST)) {
        pthread_mutex_lock(&waiter->mutex);
        pthread_cond_signal(&waiter->wake);
        pthread_mutex_unlock(&waiter->mutex);
    }
}

/* The queues of the keys: the waiters of a monitor, of a class initializer. */
#define NABU_KEYQ_BUCKETS 256

typedef struct nabu_keyq_entry {
    const void *key;
    nabu_waiter *waiter;
    struct nabu_keyq_entry *next;
} nabu_keyq_entry;

static pthread_mutex_t nabu_keyq_locks[NABU_KEYQ_BUCKETS];
static nabu_keyq_entry *nabu_keyq_heads[NABU_KEYQ_BUCKETS];
static nabu_keyq_entry *nabu_keyq_tails[NABU_KEYQ_BUCKETS];
static pthread_once_t nabu_keyq_once = PTHREAD_ONCE_INIT;

static void nabu_keyq_init(void) {
    for (int i = 0; i < NABU_KEYQ_BUCKETS; i++) pthread_mutex_init(&nabu_keyq_locks[i], NULL);
}

static unsigned nabu_keyq_bucket(const void *key) {
    return (unsigned) (((uintptr_t) key >> 4) * 2654435761u) % NABU_KEYQ_BUCKETS;
}

void nabu_keyq_add(const void *key, nabu_waiter *waiter) {
    if (!nabu_parallel) return;
    pthread_once(&nabu_keyq_once, nabu_keyq_init);
    nabu_keyq_entry *entry = (nabu_keyq_entry *) malloc(sizeof *entry);
    entry->key = key;
    entry->waiter = waiter;
    const unsigned bucket = nabu_keyq_bucket(key);
    entry->next = NULL;
    pthread_mutex_lock(&nabu_keyq_locks[bucket]);
    /* at the end: the one that waits longest is woken first */
    if (nabu_keyq_tails[bucket] != NULL) nabu_keyq_tails[bucket]->next = entry; else nabu_keyq_heads[bucket] = entry;
    nabu_keyq_tails[bucket] = entry;
    pthread_mutex_unlock(&nabu_keyq_locks[bucket]);
}

void nabu_keyq_remove(const void *key, nabu_waiter *waiter) {
    if (!nabu_parallel) return;
    pthread_once(&nabu_keyq_once, nabu_keyq_init);
    const unsigned bucket = nabu_keyq_bucket(key);
    pthread_mutex_lock(&nabu_keyq_locks[bucket]);
    nabu_keyq_entry *previous = NULL;
    for (nabu_keyq_entry *entry = nabu_keyq_heads[bucket]; entry != NULL; previous = entry, entry = entry->next) {
        if (entry->key == key && entry->waiter == waiter) {
            if (previous != NULL) previous->next = entry->next; else nabu_keyq_heads[bucket] = entry->next;
            if (nabu_keyq_tails[bucket] == entry) nabu_keyq_tails[bucket] = previous;
            free(entry);
            break;
        }
    }
    pthread_mutex_unlock(&nabu_keyq_locks[bucket]);
}

void nabu_keyq_wake_one(const void *key) {
    if (!nabu_parallel) {
        nabu_gil_signal();
        return;
    }
    pthread_once(&nabu_keyq_once, nabu_keyq_init);
    const unsigned bucket = nabu_keyq_bucket(key);
    pthread_mutex_lock(&nabu_keyq_locks[bucket]);
    for (nabu_keyq_entry *entry = nabu_keyq_heads[bucket]; entry != NULL; entry = entry->next) {
        if (entry->key == key) {
            nabu_waiter_wake(entry->waiter);
            break;
        }
    }
    pthread_mutex_unlock(&nabu_keyq_locks[bucket]);
}

void nabu_keyq_wake(const void *key) {
    if (!nabu_parallel) {
        nabu_gil_signal();
        return;
    }
    pthread_once(&nabu_keyq_once, nabu_keyq_init);
    const unsigned bucket = nabu_keyq_bucket(key);
    pthread_mutex_lock(&nabu_keyq_locks[bucket]);
    for (nabu_keyq_entry *entry = nabu_keyq_heads[bucket]; entry != NULL; entry = entry->next) {
        if (entry->key == key) nabu_waiter_wake(entry->waiter);
    }
    pthread_mutex_unlock(&nabu_keyq_locks[bucket]);
}

/*
 * Gives up the lock until nabu_gil_signal is called or the time (milliseconds, negative: no limit) has passed,
 * then waits for the turn. Returns 1 when the time passed. A signal can be for another waiter, so callers check
 * what they wait for and call again. The signal cannot be missed: the signalling thread holds the lock, and this
 * thread reads the counter before it gives the lock up.
 */
int nabu_gil_wait(int64_t timeout_ms) {
    if (!nabu_threads_active) return 1;
    struct timespec deadline;
    if (timeout_ms >= 0) {
        clock_gettime(CLOCK_REALTIME, &deadline);
        deadline.tv_sec += (time_t)(timeout_ms / 1000);
        deadline.tv_nsec += (long)(timeout_ms % 1000) * 1000000L;
        if (deadline.tv_nsec >= 1000000000L) { deadline.tv_sec++; deadline.tv_nsec -= 1000000000L; }
    }
    int timed_out = 0;
    pthread_mutex_lock(&nabu_gil_mutex);
    unsigned long seen = nabu_gil_events;
    nabu_gil_serving++;
    pthread_cond_broadcast(&nabu_gil_turn);
    while (nabu_gil_events == seen && !timed_out) {
        if (timeout_ms < 0) {
            pthread_cond_wait(&nabu_gil_event, &nabu_gil_mutex);
        } else if (pthread_cond_timedwait(&nabu_gil_event, &nabu_gil_mutex, &deadline) != 0) {
            timed_out = 1;
        }
    }
    nabu_gil_take_turn();
    pthread_mutex_unlock(&nabu_gil_mutex);
    return timed_out;
}

/*
 * A read of a volatile field. A thread that reads the same one over and over is waiting for another thread to
 * change it, and only gets a turn when it gives its own up.
 */
static __thread const void *nabu_spin_address = NULL;
static __thread unsigned nabu_spin_count = 0;

void nabu_spin_hint(const void *address) {
    if (!nabu_threads_active) return;
    if (address != nabu_spin_address) {
        nabu_spin_address = address;
        nabu_spin_count = 0;
    } else if (++nabu_spin_count >= 200) {
        nabu_spin_count = 0;
        if (nabu_wake_hook != NULL) nabu_wake_hook();
        nabu_gil_yield();
    }
}

/* Compiled loops look at nabu_safepoint_request on every jump back; while it is not 0 (another thread wants
 * the stack of a thread that never waits) they call nabu_safepoint_poll, where the wake hook gives the stack. */
volatile int nabu_safepoint_request = 0;

void nabu_safepoint_poll(void) {
    if (nabu_wake_hook != NULL) nabu_wake_hook();
}

/* -------------------------------------------------------
 * Monitor (synchronized): guarded by the GIL
 * ------------------------------------------------------- */

void nabu_monitorenter(void *obj) {
    if (obj == NULL) {
        fprintf(stderr, "nabu: synchronized on null object\n");
        abort();
    }
    nabu_object *header = (nabu_object *)obj;
    const int me = nabu_thread_id;
    if (nabu_parallel) {
        /* thread_id is the lock word: 0 free, else the owner; only the owner touches monitor_count */
        if (header->thread_id != me) {
            int free_word = 0;
            if (!__atomic_compare_exchange_n(&header->thread_id, &free_word, me, 0, __ATOMIC_ACQUIRE, __ATOMIC_RELAXED)) {
                nabu_wait_mark(3);
                nabu_waiter *waiter = nabu_waiter_self();
                nabu_keyq_add(obj, waiter);
                __atomic_add_fetch(&nabu_monitor_waiters, 1, __ATOMIC_SEQ_CST);
                for (;;) {
                    const unsigned long ticket = nabu_waiter_ticket(waiter);
                    free_word = 0;
                    if (__atomic_compare_exchange_n(&header->thread_id, &free_word, me, 0, __ATOMIC_ACQUIRE, __ATOMIC_RELAXED)) {
                        break;
                    }
                    nabu_waiter_wait(waiter, ticket, -1);
                }
                __atomic_sub_fetch(&nabu_monitor_waiters, 1, __ATOMIC_SEQ_CST);
                nabu_keyq_remove(obj, waiter);
                nabu_wait_mark(0);
            }
            header->monitor_count = 1;
        } else {
            header->monitor_count++;
        }
        return;
    }
    if (header->monitor_count > 0 && header->thread_id != me) {
        nabu_wait_mark(3);
        while (header->monitor_count > 0 && header->thread_id != me) {
            nabu_gil_wait(-1);
        }
        nabu_wait_mark(0);
    }
    header->monitor_count++;
    header->thread_id = me;
}

void nabu_monitorexit(void *obj) {
    if (obj == NULL) {
        fprintf(stderr, "nabu: synchronized on null object\n");
        abort();
    }
    nabu_object *header = (nabu_object *)obj;
    if (nabu_parallel) {
        if (--header->monitor_count == 0) {
            __atomic_store_n(&header->thread_id, 0, __ATOMIC_SEQ_CST);
            if (__atomic_load_n(&nabu_monitor_waiters, __ATOMIC_SEQ_CST) > 0) {
                nabu_keyq_wake_one(obj);
            }
        }
        return;
    }
    header->monitor_count--;
    if (header->monitor_count == 0) {
        header->thread_id = 0;
        nabu_gil_signal();
    }
}

void nabu_unresolved(const char *symbol) {
    fprintf(stderr, "nabu: call to a JDK function without an implementation: %s\n",
            symbol != NULL ? symbol : "?");
    /* Where it was called from, so the missing function can be traced to the Java code that needs it. */
    uintptr_t frames[64];
    int n = nabu_capture_frames(frames, 64);
    for (int i = 1; i < n; i++) {
        const char *name = nabu_frame_name(frames[i]);
        if (name == NULL || strcmp(name, "main") == 0) break;
        char text[512];
        snprintf(text, sizeof text, "%s", name);
        char *sep = strrchr(text, '_');
        if (sep != NULL && sep != text) *sep = '.';
        fprintf(stderr, "    at %s\n", text);
    }
    fflush(stderr);
    exit(127);
}

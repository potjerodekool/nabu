#include "nabu_runtime.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ctype.h>
#include <math.h>
#include <time.h>
#include <errno.h>
#include <pthread.h>
#ifdef _WIN32
#include <windows.h>
#include <direct.h>
#include <io.h>
#include <fcntl.h>
#include <sys/stat.h>
#else
#include <unistd.h>
#endif

#ifdef NABU_GC_BOEHM
#include <gc.h>
#define JCL_MEM(n) GC_malloc(n)
#else
#define JCL_MEM(n) calloc(1, (n))
#endif

#ifdef NABU_GC_BOEHM
#define JCL_REALLOC(p, n) GC_realloc((p), (n))
#else
#define JCL_REALLOC(p, n) realloc((p), (n))
#endif

#define BIND(ret, cname, sym, params) ret cname params __asm__(sym); ret cname params

enum {
    K_STR = 1, K_BOX, K_MAP, K_FIELD, K_ANN, K_THROW, K_CLASSOBJ, K_CTOR, K_METHOD, K_GTYPE, K_THREAD
};

enum { B_I32, B_I64, B_BOOL, B_F64, B_F32, B_I16, B_I8, B_CHAR };

static int jcl_exiting = 0;

static void jcl_sleep_ms(int64_t ms) {
#ifdef _WIN32
    Sleep((DWORD)ms);
#else
    struct timespec ts = { (time_t)(ms / 1000), (long)((ms % 1000) * 1000000L) };
    nanosleep(&ts, NULL);
#endif
}


typedef struct { int kind; char *v; } s_str;
typedef struct { int kind; int t; union { int32_t i; int64_t j; double d; float f; int16_t c; } v; } s_box;
typedef struct { int kind; void **ks, **vs; int32_t size, cap; } s_map;
typedef struct { int kind; const nabu_field_info *f; char *cls; } s_field;
/* A java.lang.Thread. state: 0 new, 1 started, 2 terminated. Threads run one at a time (see nabu_gil_*). */
typedef struct { int kind; char *name; void *target; void *threadLocals; void *inheritableThreadLocals;
                 int daemon; int priority; int64_t id;
                 int state; int interrupted; int permit; void *self; } s_thread;
typedef struct { int kind; const nabu_annotation_entry *e; } s_ann;
typedef struct { int kind; char *msg; char *cls; void *cause; } s_throw;

static void *jcl_alloc(size_t n) {
    void *p = JCL_MEM(n);
    if (!p) { fprintf(stderr, "jcl: allocation failed (%zu)\n", n); abort(); }
    return p;
}

static char *jcl_dup(const char *s) {
    if (!s) s = "";
    size_t n = strlen(s) + 1;
    char *p = (char *)jcl_alloc(n);
    memcpy(p, s, n);
    return p;
}

static char *jcl_str(const void *p) {
    if (p == NULL) return (char *)"";
    if (nabu_is_jstring(p)) return (char *)nabu_cstr(p);
    if (nabu_jcl_is_object(p)) {
        s_str *st = (s_str *)nabu_jcl_state((void *)p);
        /* Ook een Class-object (jcl_class) draagt zijn naam als tekst: de
         * Class-functies lezen de klassenaam via jcl_str, en zonder deze
         * tak gaf het resultaat van getClass() een lege naam. */
        if (st != NULL && (st->kind == K_STR || st->kind == K_CLASSOBJ)) return st->v != NULL ? st->v : (char *)"";
        return (char *)"";
    }
    return (char *)p;
}

static void *jcl_state_kind(const void *p, int kind) {
    if (p == NULL || !nabu_jcl_is_object(p)) return NULL;
    void *st = nabu_jcl_state((void *)p);
    if (st != NULL && *(int *)st == kind) return st;
    return NULL;
}

static int jcl_eq(const void *a, const void *b) {
    if (a == b) return 1;
    if (a == NULL || b == NULL) return 0;
    int oa = nabu_jcl_is_object(a);
    int ob = nabu_jcl_is_object(b);
    if (!oa && !ob) return strcmp(nabu_cstr(a), nabu_cstr(b)) == 0;
    s_box *ba = oa ? (s_box *)jcl_state_kind(a, K_BOX) : NULL;
    s_box *bb = ob ? (s_box *)jcl_state_kind(b, K_BOX) : NULL;
    if (ba != NULL && bb != NULL && ba->t == bb->t) {
        switch (ba->t) {
            case B_I32: return ba->v.i == bb->v.i;
            case B_I64: return ba->v.j == bb->v.j;
            case B_BOOL: return ba->v.i == bb->v.i;
            case B_F64: return ba->v.d == bb->v.d;
            case B_F32: return ba->v.f == bb->v.f;
            case B_I16: case B_CHAR: return ba->v.c == bb->v.c;
            case B_I8: return ba->v.i == bb->v.i;
        }
        return 0;
    }
    if (ba != NULL || bb != NULL) {
        /* One side is boxed; with only b boxed, ba was dereferenced as NULL. */
        const s_box *box = ba != NULL ? ba : bb;
        const void *oth = ba != NULL ? b : a;
        const char *s = jcl_str(oth);
        if (box->t == B_BOOL) return (strcmp(s, "true") == 0) == (box->v.i != 0);
        return 0;
    }
    /* Geen van beide is een boxed primitive. Een inhoudelijke strcmp is hier
     * alleen correct als BEIDE kanten een ECHTE string-waarde zijn (een
     * K_STR-jcl-object of een rauwe char*-literal) — dat is Java's
     * String.equals()-semantiek. jcl_str() op elk ANDER jcl-object (een
     * Class-object (K_CLASSOBJ), een custom klasse zonder eigen
     * equals()-override, een array, ...) geeft altijd "" terug (zie
     * jcl_str hierboven), dus de oude kale "return strcmp(jcl_str(a),
     * jcl_str(b)) == 0" gaf voor ÉLK PAAR niet-string-objecten
     * strcmp("", "") == 1 terug: elke twee Class-objecten (of twee
     * willekeurige andere objecten) golden zo als "gelijk". Een
     * Map<Class<?>, V>-registry (bv. picocli's eigen
     * converterRegistry = Map<Class<?>, ITypeConverter<?>>) klapte daardoor
     * ineen tot één entry: elke nieuwe put() vond de EERSTE sleutel altijd
     * "gelijk" (jcl_map_put scant lineair en overschrijft de eerste match)
     * en overschreef gewoon de waarde van die ene entry, keer op keer — de
     * laatst geregistreerde converter werd dan voor ELKE opzoeking
     * teruggegeven, ongeacht de eigenlijke Class-sleutel. Java's default
     * Object.equals() is identiteitsvergelijking; die is hierboven al als
     * 'a == b' gecheckt en gefaald, dus voor niet-strings is het antwoord
     * dan gewoon nee (0), niet een toevallige lege-string-match. */
    int aIsRealString = !oa || jcl_state_kind(a, K_STR) != NULL;
    int bIsRealString = !ob || jcl_state_kind(b, K_STR) != NULL;
    if (aIsRealString && bIsRealString) {
        return strcmp(jcl_str(a), jcl_str(b)) == 0;
    }
    return 0;
}

static int32_t jcl_hash(const void *p) {
    if (p == NULL) return 0;
    if (nabu_jcl_is_object(p)) {
        s_box *b = (s_box *)jcl_state_kind(p, K_BOX);
        if (b != NULL) {
            switch (b->t) {
                case B_I32: case B_I8: return b->v.i;
                case B_I64: return (int32_t)(b->v.j ^ (b->v.j >> 32));
                case B_F64: { uint64_t u; memcpy(&u, &b->v.d, 8); return (int32_t)(u ^ (u >> 32)); }
                case B_F32: { uint32_t u; memcpy(&u, &b->v.f, 4); return (int32_t)u; }
                default: break;
            }
        }
        return (int32_t)((intptr_t)p >> 4);
    }
    const unsigned char *s = (const unsigned char *)nabu_cstr(p);
    uint32_t h = 5381;
    while (*s) h = h * 33u + *s++;
    return (int32_t)h;
}



static void *jcl_box(int t, int64_t iv, double dv) {
    /* Bij voorkeur de uit bytecode gecompileerde wrapper: een eigen
     * box-object had de vtable van Object, zodat bv. de via Field.get gelezen
     * standaardwaarde van een boolean-optie als "java.lang.Boolean@fe47ef08"
     * naar picocli's BooleanConverter ging. */
    void *fn = nabu_box_fn(t);
    if (fn != NULL) {
        switch (t) {
            case B_I32: return ((void *(*)(int32_t))fn)((int32_t)iv);
            case B_I64: return ((void *(*)(int64_t))fn)(iv);
            case B_BOOL: return ((void *(*)(_Bool))fn)(iv != 0);
            case B_F64: return ((void *(*)(double))fn)(dv);
            case B_F32: return ((void *(*)(float))fn)((float)dv);
            case B_I16: case B_CHAR: return ((void *(*)(int16_t))fn)((int16_t)iv);
            case B_I8: return ((void *(*)(int8_t))fn)((int8_t)iv);
        }
    }
    s_box *b = (s_box *)jcl_alloc(sizeof(s_box));
    b->kind = K_BOX;
    b->t = t;
    if (t == B_F64 || t == B_F32) b->v.d = dv;
    else b->v.j = iv;
    void *obj = jcl_alloc(48 + 8);
    const char *tn = t == B_BOOL ? "java/lang/Boolean"
            : t == B_I64 ? "java/lang/Long"
            : t == B_F64 ? "java/lang/Double"
            : t == B_F32 ? "java/lang/Float"
            : t == B_I16 ? "java/lang/Short"
            : t == B_I8 ? "java/lang/Byte"
            : t == B_CHAR ? "java/lang/Character"
            : "java/lang/Integer";
    nabu_init_object(obj, tn);
    nabu_jcl_set_state(obj, b);
    return obj;
}

static void *box_i32(int32_t v) { return jcl_box(B_I32, v, 0); }
static void *box_i64(int64_t v) { return jcl_box(B_I64, v, 0); }
static void *box_bool(int v) { return jcl_box(B_BOOL, v ? 1 : 0, 0); }
static void *box_f64(double v) { return jcl_box(B_F64, 0, v); }
static void *box_i16(int32_t v) { return jcl_box(B_I16, v, 0); }

/* Een uit bytecode gecompileerde wrapper (java.lang.Integer e.d.): de waarde
 * staat in het instantieveld `value`. Retourneert 1 en vult *out bij succes.
 * picocli zet een int-optie via Field.set(obj, Integer.valueOf(3)); de
 * runtime kende alleen haar eigen box-objecten en las anders tekst. */
static int compiled_box_value(const void *p, int64_t *as_long, double *as_double) {
    const char *t = nabu_compiled_type_name(p);
    if (t == NULL || strncmp(t, "java/lang/", 10) != 0) return 0;
    intptr_t off = nabu_instance_field_offset(p, "value");
    if (off < 0) return 0;
    const char *at = (const char *)p + off;
    const char *n = t + 10;
    int64_t l = 0; double d = 0;
    if (strcmp(n, "Integer") == 0) { int32_t v; memcpy(&v, at, 4); l = v; d = v; }
    else if (strcmp(n, "Long") == 0) { int64_t v; memcpy(&v, at, 8); l = v; d = (double)v; }
    else if (strcmp(n, "Short") == 0) { int16_t v; memcpy(&v, at, 2); l = v; d = v; }
    else if (strcmp(n, "Character") == 0) { uint16_t v; memcpy(&v, at, 2); l = v; d = v; }
    else if (strcmp(n, "Byte") == 0 || strcmp(n, "Boolean") == 0) { int8_t v; memcpy(&v, at, 1); l = v; d = v; }
    else if (strcmp(n, "Double") == 0) { double v; memcpy(&v, at, 8); l = (int64_t)v; d = v; }
    else if (strcmp(n, "Float") == 0) { float v; memcpy(&v, at, 4); l = (int64_t)v; d = v; }
    else return 0;
    if (as_long != NULL) *as_long = l;
    if (as_double != NULL) *as_double = d;
    return 1;
}

static int64_t box_as_i64(const void *p) {
    int64_t compiled;
    if (compiled_box_value(p, &compiled, NULL)) return compiled;
    s_box *b = (s_box *)jcl_state_kind(p, K_BOX);
    if (b == NULL) return (int64_t)strtoll(jcl_str(p), NULL, 10);
    if (b->t == B_F64 || b->t == B_F32) return (int64_t)b->v.d;
    if (b->t == B_I16 || b->t == B_CHAR) return b->v.c;
    return b->v.j;
}


static double box_as_f64(const void *p) {
    double compiled;
    if (compiled_box_value(p, NULL, &compiled)) return compiled;
    s_box *b = (s_box *)jcl_state_kind(p, K_BOX);
    if (b == NULL) return strtod(jcl_str(p), NULL);
    if (b->t == B_F64 || b->t == B_F32) return b->v.d;
    if (b->t == B_I16 || b->t == B_CHAR) return b->v.c;
    if (b->t == B_I64) return (double)b->v.j;
    return b->v.i;
}


static void *jcl_new_str_obj(const char *v) {
    s_str *st = (s_str *)jcl_alloc(sizeof(s_str));
    st->kind = K_STR;
    st->v = jcl_dup(v);
    void *obj = jcl_alloc(48 + 8);
    nabu_init_object(obj, "java/lang/String");
    nabu_jcl_set_state(obj, st);
    return obj;
}

/* Class-objecten zijn geïnterneerd per interne naam: `X.class == o.getClass()`
 * en een Map<Class<?>, V> met identiteits-equals (Object.equals) werken alleen
 * als dezelfde naam altijd hetzelfde object oplevert. Open-addressing-tabel
 * die meegroeit; een vaste bovengrens brak de identiteit zodra een programma
 * (picocli: honderden klassen) er overheen ging. */
static void **jcl_class_table = NULL;
static uint32_t jcl_class_cap = 0, jcl_class_n = 0;

static uint32_t jcl_class_slot(void **table, uint32_t cap, const char *iname) {
    uint32_t h = 2166136261u;
    for (const unsigned char *s = (const unsigned char *)iname; *s; s++) h = (h ^ *s) * 16777619u;
    uint32_t i = h & (cap - 1);
    while (table[i] != NULL) {
        s_str *st = (s_str *)nabu_jcl_state(table[i]);
        if (strcmp(st->v, iname) == 0) break;
        i = (i + 1) & (cap - 1);
    }
    return i;
}

static void *jcl_class(const char *iname) {
    if (iname == NULL) iname = "";
    /* Eén Class-object per klasse, ongeacht de naamvorm: "pkg.Cls",
     * "pkg/Cls" en "Lpkg/Cls;" interneren naar de interne vorm "pkg/Cls"
     * (arrays houden hun descriptor, met slashes). Anders zijn
     * Class-vergelijkingen (picocli's isNonDefault) onterecht ongelijk. */
    char canon[512];
    size_t len = strlen(iname);
    if (len > 2 && len < sizeof canon && iname[0] == 'L' && iname[len - 1] == ';') {
        memcpy(canon, iname + 1, len - 2);
        canon[len - 2] = '\0';
        iname = canon;
        len -= 2;
    } else if (len < sizeof canon && strchr(iname, '.') != NULL) {
        memcpy(canon, iname, len + 1);
        iname = canon;
    }
    if (iname == canon) {
        for (size_t i = 0; i < len; i++) if (canon[i] == '.') canon[i] = '/';
    }
    if (jcl_class_table != NULL) {
        void *found = jcl_class_table[jcl_class_slot(jcl_class_table, jcl_class_cap, iname)];
        if (found != NULL) return found;
    }
    if ((jcl_class_n + 1) * 2 > jcl_class_cap) {
        uint32_t cap = jcl_class_cap == 0 ? 256 : jcl_class_cap * 2;
        void **table = (void **)jcl_alloc(sizeof(void *) * cap);
        memset(table, 0, sizeof(void *) * cap);
        for (uint32_t i = 0; i < jcl_class_cap; i++) {
            if (jcl_class_table[i] != NULL) {
                const char *n = ((s_str *)nabu_jcl_state(jcl_class_table[i]))->v;
                table[jcl_class_slot(table, cap, n)] = jcl_class_table[i];
            }
        }
        jcl_class_table = table;
        jcl_class_cap = cap;
    }
    s_str *st = (s_str *)jcl_alloc(sizeof(s_str));
    st->kind = K_CLASSOBJ;
    st->v = jcl_dup(iname);
    void *obj = jcl_alloc(48 + 8);
    nabu_init_object(obj, "java/lang/Class");
    nabu_jcl_set_state(obj, st);
    jcl_class_table[jcl_class_slot(jcl_class_table, jcl_class_cap, iname)] = obj;
    jcl_class_n++;
    return obj;
}

/* Class-literal (`X.class`, ldc van een Type) uit gecompileerde code. De
 * backend geeft de interne naam ("pkg/Cls", "[pkg/Cls") of, voor een
 * primitief, de JVM-descriptor ("I"); die laatste wordt de naam die
 * Class.getPrimitiveClass ("int") ook gebruikt, zodat int.class == Integer.TYPE. */
void *nabu_class_literal(const char *iname) {
    if (iname != NULL && iname[0] != '\0' && iname[1] == '\0') {
        switch (iname[0]) {
            case 'Z': return jcl_class("boolean");
            case 'B': return jcl_class("byte");
            case 'C': return jcl_class("char");
            case 'S': return jcl_class("short");
            case 'I': return jcl_class("int");
            case 'J': return jcl_class("long");
            case 'F': return jcl_class("float");
            case 'D': return jcl_class("double");
            case 'V': return jcl_class("void");
        }
    }
    return jcl_class(iname);
}





/* Array-layout: i32 lengte op offset 0, i32 elementgrootte (bytes) op
 * offset 4, data op offset 8. De backend schrijft de elementgrootte bij elke
 * allocatie; zonder die grootte kan een generieke kopie (arraycopy, clone) niet
 * weten hoeveel bytes een element beslaat. */
static void *jcl_arr_ref(int32_t n) {
    if (n < 0) n = 0;
    void *p = jcl_alloc(8 + (size_t)n * sizeof(void *));
    ((int32_t *)p)[0] = n;
    ((int32_t *)p)[1] = (int32_t)sizeof(void *);
    return p;
}


static int32_t jcl_arr_len(void *a) { return a != NULL ? *(int32_t *)a : 0; }

/* Elementgrootte van een array; 8 (referentie) voor een array zonder geldige
 * grootte in de header, zoals vóór de elementgrootte bestond. */
static size_t jcl_arr_esize(const void *a) {
    int32_t es = a != NULL ? (((const int32_t *)a)[1] & 0xff) : 0;
    return (es == 1 || es == 2 || es == 4 || es == 8) ? (size_t)es : 8;
}

/* The class of an array: the upper 24 bits of the element-size word are an
 * array type id (see nabu_array_type_name); 0 means unknown. */
static const char *jcl_arr_type_name(const void *a) {
    int32_t id = a != NULL ? (int32_t)(((const uint32_t *)a)[1] >> 8) : 0;
    return nabu_array_type_name(id);
}
/* The array class of p if p looks like an array the backend (or
 * Array.newInstance) allocated: a non-negative length, a valid element size
 * and a known type index. Used where only an Object reference is known
 * (Object.getClass, hashCode); a C string or another object does not match
 * (its fifth byte is no element size, or its first word is a type pointer). */
static const char *jcl_typed_array_name(const void *p) {
    if (p == NULL) return NULL;
    const int32_t *w = (const int32_t *)p;
    int32_t es = w[1] & 0xff;
    if (w[0] < 0 || !(es == 1 || es == 2 || es == 4 || es == 8)) return NULL;
    return jcl_arr_type_name(p);
}
static void jcl_arr_set_type(void *a, const char *name) {
    nabu_array_tag(a, name);
}
/* A reference array of n elements with the given array class. */
static void *jcl_arr_typed(int32_t n, const char *name) {
    void *a = jcl_arr_ref(n);
    nabu_array_tag(a, name);
    return a;
}

static void *jcl_arr_elem(void *a, int32_t i) {
    if (a == NULL) return NULL;
    int32_t n = *(int32_t *)a;
    if (i < 0 || i >= n) return NULL;
    return *((void **)((char *)a + 8) + i);
}

static void jcl_arr_set(void *a, int32_t i, void *v) {
    if (a == NULL) return;
    int32_t n = *(int32_t *)a;
    if (i < 0 || i >= n) return;
    *((void **)((char *)a + 8) + i) = v;
}

static void *jcl_strs(const char *comma_text, int32_t count_hint) {
    int32_t n = 1;
    if (comma_text != NULL && comma_text[0] != 0) {
        for (const char *p = comma_text; *p; p++) if (*p == ',') n++;
    } else n = 0;
    if (count_hint == 0) n = 0;
    void *arr = jcl_arr_typed(n, "[Ljava/lang/String;");
    if (n == 0) return arr;
    int32_t i = 0;
    const char *p = comma_text;
    const char *start = p;
    while (i < n) {
        if (*p == ',' || *p == 0) {
            size_t len = (size_t)(p - start);
            char *s = (char *)jcl_alloc(len + 1);
            memcpy(s, start, len);
            s[len] = 0;
            jcl_arr_set(arr, i++, s);
            start = p + 1;
            if (*p == 0) break;
        }
        p++;
    }
    while (i < n) jcl_arr_set(arr, i++, jcl_dup(""));
    return arr;
}

static void *jcl_empty_strs(void) { return jcl_arr_typed(0, "[Ljava/lang/String;"); }

/*
 * Bouwt een java.lang.String[] (args) op uit de C-style argv die het
 * besturingssysteem aan main(argc, argv) geeft. argv[0] is het programmapad
 * (zoals in C) en wordt NIET meegenomen -- dit spiegelt Java's
 * String[] args, dat ook geen programmanaam bevat.
 *
 * Niet-static (geen BIND nodig: geen java.*-gestippelde symboolnaam) zodat
 * de door NativeLLVMBackend.ensureMainEntry gesynthetiseerde main()-bridge
 * 'm als gewone C-functie kan aanroepen; zie LLVMModuleEmitter.declareRuntimeHelpers.
 */
void *nabu_args_from_argv(int32_t argc, char **argv) {
    int32_t n = argc > 1 ? argc - 1 : 0;
    void *arr = jcl_arr_typed(n, "[Ljava/lang/String;");
    for (int32_t i = 0; i < n; i++) {
        jcl_arr_set(arr, i, nabu_jstring_new(argv[i + 1]));
    }
    return arr;
}

static const nabu_annotation_value *ann_v(const void *ann, const char *name) {
    s_ann *st = (s_ann *)jcl_state_kind(ann, K_ANN);
    if (st == NULL || st->e == NULL) return NULL;
    for (int i = 0; i < st->e->value_count; i++) {
        const nabu_annotation_value *v = &st->e->values[i];
        if (v->name != NULL && strcmp(v->name, name) == 0) return v;
    }
    return NULL;
}

static const char *ann_text(const void *ann, const char *name, const char *dflt) {
    const nabu_annotation_value *v = ann_v(ann, name);
    if (v == NULL || v->text == NULL) return dflt;
    return v->text;
}

static double ann_num(const void *ann, const char *name, double dflt) {
    const nabu_annotation_value *v = ann_v(ann, name);
    if (v == NULL) return dflt;
    if (v->is_string && v->text != NULL) return strtod(v->text, NULL);
    return v->num_value;
}

static int ann_bool(const void *ann, const char *name, int dflt) {
    const nabu_annotation_value *v = ann_v(ann, name);
    if (v == NULL) return dflt;
    if (v->is_string && v->text != NULL) return strcmp(v->text, "true") == 0;
    return v->num_value != 0;
}





static void *jcl_throw(const char *cls, const char *super, const char *msg) {
    void *real = nabu_new_exception(cls, msg);
    if (real != NULL) return real;
    s_throw *st = (s_throw *)jcl_alloc(sizeof(s_throw));
    st->kind = K_THROW;
    st->msg = jcl_dup(msg);
    st->cls = jcl_dup(cls);
    st->cause = NULL;
    void *obj = jcl_alloc(48 + 8);
    nabu_init_object_ex(obj, cls, super);
    nabu_jcl_set_state(obj, st);
    return obj;
}

static char *jcl_concat(const char *a, const char *b) {
    size_t na = strlen(a), nb = strlen(b);
    char *o = (char *)jcl_alloc(na + nb + 1);
    memcpy(o, a, na);
    memcpy(o + na, b, nb + 1);
    return o;
}


/* Annotatie-elementen. De compiler geeft elk element van een annotatie-
 * interface een body (AnnotationAccessors) die een van deze helpers aanroept
 * met de elementnaam en de default uit het AnnotationDefault-attribuut. De
 * waarde komt uit de reflectie-metadata van de annotatie-instantie; ontbreekt
 * die, dan geldt de default. */
static void *jcl_strs_of(const char *text) {
    if (text == NULL || text[0] == 0) return jcl_empty_strs();
    void *arr = jcl_strs(text, 1);
    int32_t n = jcl_arr_len(arr);
    void **items = (void **)((char *)arr + 8);
    for (int32_t i = 0; i < n; i++) items[i] = nabu_jstring_new(nabu_cstr(items[i]));
    return arr;
}

static void *jcl_classes_of(const char *text) {
    void *names = (text == NULL || text[0] == 0) ? jcl_arr_ref(0) : jcl_strs(text, 1);
    int32_t n = jcl_arr_len(names);
    void *arr = jcl_arr_typed(n, "[Ljava/lang/Class;");
    void **src = (void **)((char *)names + 8);
    void **dst = (void **)((char *)arr + 8);
    for (int32_t i = 0; i < n; i++) dst[i] = jcl_class(nabu_cstr(src[i]));
    return arr;
}

BIND(void *, jcl_ann_text, "nabu.runtime.Annotations_text", (void *a, void *name, void *dflt)) {
    const nabu_annotation_value *v = ann_v(a, nabu_cstr(name));
    if (v == NULL || v->text == NULL) return dflt;
    return nabu_jstring_new(v->text);
}

BIND(void *, jcl_ann_texts, "nabu.runtime.Annotations_texts", (void *a, void *name, void *dflt)) {
    const nabu_annotation_value *v = ann_v(a, nabu_cstr(name));
    return jcl_strs_of(v != NULL ? v->text : (dflt != NULL ? nabu_cstr(dflt) : NULL));
}

BIND(int, jcl_ann_bool, "nabu.runtime.Annotations_bool", (void *a, void *name, int dflt)) {
    return ann_bool(a, nabu_cstr(name), dflt & 1);
}

BIND(double, jcl_ann_number, "nabu.runtime.Annotations_number", (void *a, void *name, double dflt)) {
    return ann_num(a, nabu_cstr(name), dflt);
}

/* Class-waardige elementen: een (geïnterneerd) Class-object, geen String.
 * picocli vergelijkt de waarde met een class-literal (isNonDefault). */
BIND(void *, jcl_ann_type, "nabu.runtime.Annotations_type", (void *a, void *name, void *dflt)) {
    const char *t = ann_text(a, nabu_cstr(name), NULL);
    if (t == NULL || *t == 0) t = dflt != NULL ? nabu_cstr(dflt) : NULL;
    return t != NULL ? jcl_class(t) : NULL;
}

BIND(void *, jcl_ann_types, "nabu.runtime.Annotations_types", (void *a, void *name, void *dflt)) {
    const nabu_annotation_value *v = ann_v(a, nabu_cstr(name));
    return jcl_classes_of(v != NULL ? v->text : (dflt != NULL ? nabu_cstr(dflt) : NULL));
}

static void *jcl_ann_obj(const nabu_annotation_entry *e);

/* Annotations nested in an annotation: entries of the same class table
 * owned by "^<index of the enclosing entry>.<element>" ("#i" for the i-th
 * of an array). */
static char *jcl_nested_key(void *a, const char *name, const nabu_reflection_info **info) {
    s_ann *st = (s_ann *)jcl_state_kind(a, K_ANN);
    if (st == NULL) return NULL;
    int index;
    *info = nabu_annotation_owner(st->e, &index);
    if (*info == NULL) return NULL;
    char prefix[32];
    snprintf(prefix, sizeof prefix, "^%d.", index);
    return jcl_concat(prefix, name);
}
static const nabu_annotation_entry *jcl_entry_owned(const nabu_reflection_info *info, const char *owner) {
    for (int i = 0; i < info->annotation_count; i++) {
        if (info->annotations[i].owner != NULL && strcmp(info->annotations[i].owner, owner) == 0) {
            return &info->annotations[i];
        }
    }
    return NULL;
}
/* An element whose value is an annotation (null if it has none: the
 * default of such an element is not in the metadata). */
BIND(void *, jcl_ann_annotation, "nabu.runtime.Annotations_annotation", (void *a, void *name, void *type)) {
    (void)type;
    const nabu_reflection_info *info;
    char *key = jcl_nested_key(a, nabu_cstr(name), &info);
    const nabu_annotation_entry *e = key != NULL ? jcl_entry_owned(info, key) : NULL;
    return e != NULL ? jcl_ann_obj(e) : NULL;
}
/* An element whose value is an array of annotations of `type` (internal name). */
BIND(void *, jcl_ann_annotations, "nabu.runtime.Annotations_annotations", (void *a, void *name, void *type)) {
    const nabu_reflection_info *info;
    char *key = jcl_nested_key(a, nabu_cstr(name), &info);
    int32_t count = 0;
    while (key != NULL) {
        char index[16];
        snprintf(index, sizeof index, "#%d", (int)count);
        if (jcl_entry_owned(info, jcl_concat(key, index)) == NULL) break;
        count++;
    }
    void *arr = jcl_arr_ref(count);
    jcl_arr_set_type(arr, jcl_concat(jcl_concat("[L", nabu_cstr(type)), ";"));
    for (int32_t i = 0; i < count; i++) {
        char index[16];
        snprintf(index, sizeof index, "#%d", (int)i);
        jcl_arr_set(arr, i, jcl_ann_obj(jcl_entry_owned(info, jcl_concat(key, index))));
    }
    return arr;
}
/* An element whose value is an array of primitives: the metadata (or the
 * default) holds its items as comma-separated text; `descriptor` is the
 * array's ("[I", "[Z", ...). A char item is the character itself. */
BIND(void *, jcl_ann_primitives, "nabu.runtime.Annotations_primitives", (void *a, void *name, void *dflt, void *descriptor)) {
    const nabu_annotation_value *v = ann_v(a, nabu_cstr(name));
    const char *text = v != NULL && v->text != NULL ? v->text : (dflt != NULL ? nabu_cstr(dflt) : "");
    const char t = nabu_cstr(descriptor)[1];
    int32_t size = t == 'J' || t == 'D' ? 8 : t == 'I' || t == 'F' ? 4 : t == 'C' || t == 'S' ? 2 : 1;
    int32_t count = text[0] == 0 ? 0 : 1;
    for (const char *c = text; *c; c++) if (*c == ',') count++;
    void *arr = jcl_alloc(8 + (size_t)count * (size_t)size);
    ((int32_t *)arr)[0] = count;
    ((int32_t *)arr)[1] = size;
    jcl_arr_set_type(arr, nabu_cstr(descriptor));
    char *data = (char *)arr + 8;
    const char *item = text;
    for (int32_t i = 0; i < count; i++) {
        const char *next = strchr(item, ',');
        size_t len = next != NULL ? (size_t)(next - item) : strlen(item);
        char buf[64];
        if (len >= sizeof buf) len = sizeof buf - 1;
        memcpy(buf, item, len);
        buf[len] = 0;
        switch (t) {
            case 'Z': ((uint8_t *)data)[i] = strcmp(buf, "true") == 0 || strcmp(buf, "1") == 0; break;
            case 'B': ((int8_t *)data)[i] = (int8_t)strtol(buf, NULL, 10); break;
            case 'S': ((int16_t *)data)[i] = (int16_t)strtol(buf, NULL, 10); break;
            case 'C': ((uint16_t *)data)[i] = len == 1 ? (uint16_t)(unsigned char)buf[0] : (uint16_t)strtol(buf, NULL, 10); break;
            case 'I': ((int32_t *)data)[i] = (int32_t)strtol(buf, NULL, 10); break;
            case 'J': ((int64_t *)data)[i] = strtoll(buf, NULL, 10); break;
            case 'F': ((float *)data)[i] = strtof(buf, NULL); break;
            case 'D': ((double *)data)[i] = strtod(buf, NULL); break;
        }
        if (next == NULL) break;
        item = next + 1;
    }
    return arr;
}

BIND(void *, jcl_java_lang_Object_clone, "java.lang.Object_clone", (void *self)) {
    if (self == NULL) return NULL;
    if (nabu_compiled_type_name(self) != NULL) {
        /* Gecompileerd object (Cloneable, bv. sun's Bundles$CacheKey): een
         * ondiepe kopie van header + velden, met een eigen (vrije) monitor.
         * Werd eerder als array behandeld: de type-pointer gaf de "lengte"
         * en memcpy liep het geheugen uit. */
        const nabu_type_info *ti = (const nabu_type_info *)((nabu_object *)self)->type;
        size_t sz = ti->size > NABU_HEADER_SIZE ? ti->size : NABU_HEADER_SIZE;
        void *c = jcl_alloc(sz);
        memcpy(c, self, sz);
        ((nabu_object *)c)->monitor_count = 0;
        ((nabu_object *)c)->thread_id = 0;
        return c;
    }
    if (!nabu_jcl_is_object(self)) {
        int32_t len = *(int32_t *)self;
        if (len < 0) len = 0;
        size_t es = jcl_arr_esize(self);
        void *c = jcl_alloc(8 + (size_t)len * es + 1);
        memcpy(c, self, 8 + (size_t)len * es);
        return c;
    }
    nabu_object *h = (nabu_object *)self;
    nabu_type_info *ti = (nabu_type_info *)h->type;
    size_t sz = (ti != NULL && ti->size > 40) ? ti->size : 48;
    void *c = jcl_alloc(sz);
    memcpy(c, self, sz);
    return c;
}
static const char *jcl_compiled_type_name(const void *p) { return nabu_compiled_type_name(p); }

BIND(void *, jcl_java_lang_Object_getClass, "java.lang.Object_getClass", (void *self)) {
    if (self == NULL) return jcl_class("");
    if (nabu_is_jstring(self)) return jcl_class("java/lang/String");
    if (!nabu_jcl_is_object(self)) {
        /* Een door de backend gecompileerd object: zijn type-info is een
         * global in het programma zelf (niet in de type-cache van de
         * runtime) en draagt de interne klassenaam. */
        const char *compiled = jcl_compiled_type_name(self);
        if (compiled == NULL) {
            const char *array = jcl_typed_array_name(self);
            if (array != NULL) return jcl_class(array);
        }
        return jcl_class(compiled != NULL ? compiled : "java/lang/String");
    }
    nabu_object *h = (nabu_object *)self;
    nabu_type_info *ti = (nabu_type_info *)h->type;
    return jcl_class(ti != NULL && ti->name != NULL ? ti->name : "");
}
BIND(int32_t, jcl_java_lang_Object_hashCode, "java.lang.Object_hashCode", (void *self)) {
    if (self == NULL) return 0;
    /* Een gecompileerd object: identiteits-hash. Eerder viel het in de
     * C-string-tak en hashte de header-bytes (begint met de type-pointer):
     * alle objecten van één klasse kregen dezelfde hash, waardoor HashMap
     * buckets ging treeifyen (DateTimeFormatterBuilder.<clinit>). */
    if (nabu_compiled_type_name(self) != NULL) return (int32_t)((intptr_t)self >> 4);
    /* An array: identity as well (it was hashed as a C string). */
    if (jcl_typed_array_name(self) != NULL) return (int32_t)((intptr_t)self >> 4);
    if (!nabu_jcl_is_object(self)) return jcl_hash(self);
    s_str *st = (s_str *)jcl_state_kind(self, K_STR);
    if (st != NULL && st->v != NULL) return jcl_hash(st->v);
    return (int32_t)((intptr_t)self >> 4);
}




/* StringBuilder komt volledig uit JDK-bytecode. De vroegere C-varianten van
 * append/insert/<init> vervingen de gecompileerde bodies: hun tekst belandde
 * in een losse C-buffer (een gecompileerd object draagt geen jcl-state) en
 * toString() gaf "" (bv. LocaleData's "sun.text.resources" + ".FormatData"). */







BIND(void, jcl_java_lang_System_arraycopy, "java.lang.System_arraycopy", (void *src, int32_t srcPos, void *dst, int32_t dstPos, int32_t len)) {
    /* The exceptions were created but never thrown: a bad copy went on. */
    if (src == NULL || dst == NULL) { nabu_throw(jcl_throw("java/lang/NullPointerException", "java/lang/RuntimeException", "arraycopy")); return; }
    if (len <= 0) return;
    if (srcPos < 0 || dstPos < 0 || srcPos + len > jcl_arr_len(src) || dstPos + len > jcl_arr_len(dst)) {
        nabu_throw(jcl_throw("java/lang/ArrayIndexOutOfBoundsException", "java/lang/IndexOutOfBoundsException", "arraycopy"));
        return;
    }
    /* De elementgrootte van de bron bepaalt de stap: met een vaste 8 schreef
     * een kopie van een byte[] (String/StringBuilder-buffers) tot 8x voorbij
     * het einde van de doel-array, dwars door de heap. */
    size_t es = jcl_arr_esize(src);
    memmove((char *)dst + 8 + (size_t)dstPos * es, (const char *)src + 8 + (size_t)srcPos * es, (size_t)len * es);
}
BIND(int64_t, jcl_java_lang_System_currentTimeMillis, "java.lang.System_currentTimeMillis", (void)) {
    return (int64_t)(time(NULL) * 1000);
}
BIND(int64_t, jcl_java_lang_System_nanoTime, "java.lang.System_nanoTime", (void)) {
    return (int64_t)clock() * (1000000000LL / CLOCKS_PER_SEC);
}
BIND(void, jcl_java_lang_System_exit, "java.lang.System_exit", (int32_t code)) { fflush(NULL); jcl_exiting = 1; exit(code); }
static void * jcl_java_lang_System_getenv (void *name) {
    const char *v = getenv(jcl_str(name));
    /* Kopie: zie jcl_env_or (8-byte-header-herkenning op een getenv-pointer). */
    return v != NULL ? (void *)jcl_dup(v) : NULL;
}
BIND(void *, jcl_java_lang_System_getenv_jstr, "java.lang.System_getenv", (void *name)) {
    void *r = (void *)jcl_java_lang_System_getenv(name);
    return r == NULL ? NULL : nabu_jstring_new(nabu_cstr(r));
}
BIND(int32_t, jcl_java_lang_System_identityHashCode, "java.lang.System_identityHashCode", (void *o)) {
    return o == NULL ? 0 : (int32_t)((intptr_t)o >> 4);
}
static void *jcl_props = NULL;
static void jcl_props_init(void) {
    if (jcl_props != NULL) return;
    s_map *m = (s_map *)jcl_alloc(sizeof(s_map));
    m->kind = K_MAP;
    m->cap = 8;
    m->ks = (void **)jcl_alloc(sizeof(void *) * 8);
    m->vs = (void **)jcl_alloc(sizeof(void *) * 8);
    jcl_props = jcl_alloc(48 + 8);
    nabu_init_object(jcl_props, "java/util/Properties");
    nabu_jcl_set_state(jcl_props, m);
}
static void jcl_map_put(void *map, void *k, void *v);
static void *jcl_map_get(void *map, void *k);
/* Gekopieerd naar de eigen heap: een getenv-pointer wijst in het
 * omgevingsblok, en nabu_is_jstring leest bij het herkennen 8 bytes vanaf het
 * begin — een korte waarde vlak voor een paginagrens (bv. USERPROFILE) gaf
 * dan een access violation. */
static const char *jcl_env_or(const char *name, const char *fallback) {
    const char *v = getenv(name);
    return jcl_dup(v != NULL && v[0] != '\0' ? v : fallback);
}

/* De VM-afhankelijke eigenschappen die de JDK-bytecode verplicht verwacht
 * (jdk.internal.util.StaticProperty gooit InternalError bij een ontbrekende):
 * uit de omgeving waar dat kan. java.home is de map van het programma zelf —
 * een native programma heeft geen JDK-installatie. De encodings zijn UTF-8:
 * de runtime werkt met UTF-8-C-strings, en zo blijven extra charset-klassen
 * (Cp1252) buiten de sluiting. */
static void jcl_props_put_system(void) {
    char buf[1024];
#ifdef _WIN32
    DWORD n = GetModuleFileNameA(NULL, buf, (DWORD)sizeof buf);
    if (n > 0 && n < sizeof buf) {
        char *slash = strrchr(buf, '\\');
        if (slash != NULL) *slash = '\0';
        jcl_map_put(jcl_props, (void *)"java.home", (void *)jcl_dup(buf));
    } else {
        jcl_map_put(jcl_props, (void *)"java.home", (void *)".");
    }
    n = GetTempPathA((DWORD)sizeof buf, buf);
    if (n > 1 && n < sizeof buf && (buf[n - 1] == '\\' || buf[n - 1] == '/')) buf[n - 1] = '\0';
    jcl_map_put(jcl_props, (void *)"java.io.tmpdir",
            (void *)(n > 0 && n < sizeof buf ? jcl_dup(buf) : jcl_env_or("TEMP", ".")));
    jcl_map_put(jcl_props, (void *)"user.dir",
            (void *)(_getcwd(buf, (int)sizeof buf) != NULL ? jcl_dup(buf) : "."));
    jcl_map_put(jcl_props, (void *)"user.home", (void *)jcl_env_or("USERPROFILE", "."));
    jcl_map_put(jcl_props, (void *)"user.name", (void *)jcl_env_or("USERNAME", "user"));
    jcl_map_put(jcl_props, (void *)"os.version", (void *)"10.0");
#else
    jcl_map_put(jcl_props, (void *)"java.home", (void *)".");
    jcl_map_put(jcl_props, (void *)"java.io.tmpdir", (void *)jcl_env_or("TMPDIR", "/tmp"));
    jcl_map_put(jcl_props, (void *)"user.dir",
            (void *)(getcwd(buf, sizeof buf) != NULL ? jcl_dup(buf) : "."));
    jcl_map_put(jcl_props, (void *)"user.home", (void *)jcl_env_or("HOME", "."));
    jcl_map_put(jcl_props, (void *)"user.name", (void *)jcl_env_or("USER", "user"));
    jcl_map_put(jcl_props, (void *)"os.version", (void *)"0");
#endif
    /* Eigen systeemeigenschappen, zoals -Dkey=value op de JVM: een native
     * programma heeft geen JVM-opties, dus via NABU_PROPERTIES="k=v;k2=v2"
     * (bv. NABU_PROPERTIES=picocli.trace=DEBUG). */
    /* Locale-data: alleen de FALLBACK-adapter (root-locale uit java.base).
     * De standaard CLDR-adapter ontdekt zijn data via ServiceLoader over de
     * module-layers, wat een native programma niet heeft. Overschrijfbaar
     * via NABU_PROPERTIES hieronder. */
    jcl_map_put(jcl_props, (void *)"java.locale.providers", (void *)"FALLBACK");
    const char *extra = getenv("NABU_PROPERTIES");
    if (extra != NULL && extra[0] != '\0') {
        char *copy = jcl_dup(extra);
        for (char *entry = copy; entry != NULL && *entry != '\0';) {
            char *next = strchr(entry, ';');
            if (next != NULL) *next++ = '\0';
            char *eq = strchr(entry, '=');
            if (eq != NULL && eq != entry) {
                *eq = '\0';
                jcl_map_put(jcl_props, (void *)jcl_dup(entry), (void *)jcl_dup(eq + 1));
            }
            entry = next;
        }
    }
    jcl_map_put(jcl_props, (void *)"native.encoding", (void *)"UTF-8");
    jcl_map_put(jcl_props, (void *)"file.encoding", (void *)"UTF-8");
    jcl_map_put(jcl_props, (void *)"sun.jnu.encoding", (void *)"UTF-8");
    jcl_map_put(jcl_props, (void *)"user.language", (void *)"en");
}

/* De runtime-eigen property-tabel (C-strings); bron voor zowel
 * System.getProperty als de gecompileerde Properties hieronder. */
static void *jcl_props_table(void) {
    jcl_props_init();
    if (jcl_map_get(jcl_props, "os.name") == NULL) {
        jcl_map_put(jcl_props, (void *)"os.name", (void *)"Windows 11");
        jcl_map_put(jcl_props, (void *)"os.arch", (void *)"amd64");
        jcl_map_put(jcl_props, (void *)"file.separator", (void *)"\\");
        jcl_map_put(jcl_props, (void *)"line.separator", (void *)"\r\n");
        jcl_map_put(jcl_props, (void *)"path.separator", (void *)";");
        jcl_map_put(jcl_props, (void *)"java.version", (void *)"17.0.0");
        jcl_props_put_system();
    }
    return jcl_props;
}

/* Een échte, uit bytecode gecompileerde java.util.Properties met dezelfde
 * inhoud als de runtime-tabel (zie nabu_properties_meta). Gecompileerde
 * aanroepers dispatchen via de vtable van Properties; het runtime-eigen
 * object had alleen die van Object, en bv. StaticProperty.getProperty
 * sprong dan naar een slot voorbij het einde van die tabel. */
/* Een leeg object van een gecompileerde klasse, met haar header. */
static void *jcl_new_compiled(void *type, void *vtable, void *itable, int64_t size) {
    void *self = jcl_alloc((size_t)size);
    nabu_object *h = (nabu_object *)self;
    h->type = type;
    h->vtable = vtable;
    h->itable = itable;
    return self;
}

static void *jcl_compiled_props = NULL;
static void *jcl_compiled_properties(void) {
    if (jcl_compiled_props != NULL) return jcl_compiled_props;
    const nabu_properties_meta_t *m = &nabu_properties_meta;
    void *self = jcl_new_compiled(m->type, m->vtable, m->itable, m->size);
    m->init(self);
    s_map *t = (s_map *)jcl_state_kind(jcl_props_table(), K_MAP);
    for (int32_t i = 0; t != NULL && i < t->size; i++) {
        m->set_property(self, nabu_jstring_new(jcl_str(t->ks[i])),
                nabu_jstring_new(jcl_str(t->vs[i])));
    }
    jcl_compiled_props = self;
    return self;
}

/* --- java.io.FileDescriptor / FileOutputStream natives --------------- */

BIND(int64_t, jcl_java_io_FileDescriptor_getHandle, "java.io.FileDescriptor_getHandle", (int32_t fd)) {
#ifdef _WIN32
    DWORD which = fd == 0 ? STD_INPUT_HANDLE : fd == 1 ? STD_OUTPUT_HANDLE : fd == 2 ? STD_ERROR_HANDLE : 0;
    return which != 0 ? (int64_t)(intptr_t)GetStdHandle(which) : -1;
#else
    (void)fd;
    return -1;
#endif
}
BIND(uint8_t, jcl_java_io_FileDescriptor_getAppend, "java.io.FileDescriptor_getAppend", (int32_t fd)) { (void)fd; return 0; }
/* The int field `fd` of a FileDescriptor; files opened by open0 are CRT
 * descriptors above 2. */
static int32_t *jcl_fd_slot(void *fdObj) {
    intptr_t off = fdObj != NULL ? nabu_instance_field_offset(fdObj, "fd") : -1;
    return off >= 0 ? (int32_t *)((char *)fdObj + off) : NULL;
}
BIND(void, jcl_java_io_FileDescriptor_close0, "java.io.FileDescriptor_close0", (void *self)) {
    int32_t *slot = jcl_fd_slot(self);
    if (slot != NULL && *slot > 2) {
#ifdef _WIN32
        _close(*slot);
#else
        close(*slot);
#endif
        *slot = -1;
    }
}

/* Het fd-nummer van een gecompileerde FileOutputStream: this.fd.fd. */
static int32_t jcl_fos_fd(void *self) {
    intptr_t off = nabu_instance_field_offset(self, "fd");
    if (off < 0) return -1;
    void *fdObj = *(void **)((char *)self + off);
    intptr_t fdOff = nabu_instance_field_offset(fdObj, "fd");
    if (fdObj == NULL || fdOff < 0) return -1;
    return *(int32_t *)((char *)fdObj + fdOff);
}

static void jcl_fos_write(void *self, const void *data, size_t len) {
    int32_t fd = jcl_fos_fd(self);
    if (fd > 2) {
        /* A file opened by FileOutputStream.open0: a CRT descriptor. */
        const char *p = (const char *)data;
        while (len > 0) {
#ifdef _WIN32
            int n = _write(fd, p, (unsigned int)len);
#else
            ssize_t n = write(fd, p, len);
#endif
            if (n <= 0) {
                {
                    char reason[96];
                    snprintf(reason, sizeof reason, "Write error (fd %d, %s)", (int)fd, strerror(errno));
                    nabu_throw(jcl_throw("java/io/IOException", "java/lang/Exception", jcl_dup(reason)));
                }
                return;
            }
            p += n;
            len -= (size_t)n;
        }
        return;
    }
    FILE *f = fd == 1 ? stdout : fd == 2 ? stderr : NULL;
    if (f == NULL) {
        nabu_throw(jcl_throw("java/io/IOException", "java/lang/Exception", "Stream Closed"));
        return;
    }
#ifdef _WIN32
    /* FileOutputStream writes raw bytes; the JDK has already written the
     * line separator ("\r\n"). In text mode the CRT made it "\r\r\n". */
    static int binary_set[3] = {0, 0, 0};
    if (!binary_set[fd]) {
        fflush(f);
        _setmode(_fileno(f), _O_BINARY);
        binary_set[fd] = 1;
    }
#endif
    fwrite(data, 1, len, f);
    fflush(f);
}

BIND(void, jcl_java_io_FileOutputStream_writeBytes, "java.io.FileOutputStream_writeBytes",
     (void *self, void *b, int32_t off, int32_t len, uint8_t append)) {
    (void)append;
    if (b == NULL) { nabu_throw(jcl_throw("java/lang/NullPointerException", "java/lang/RuntimeException", NULL)); return; }
    if (off < 0 || len < 0 || off + len > jcl_arr_len(b)) {
        nabu_throw(jcl_throw("java/lang/IndexOutOfBoundsException", "java/lang/RuntimeException", NULL));
        return;
    }
    jcl_fos_write(self, (const char *)b + 8 + off, (size_t)len);
}
BIND(void, jcl_java_io_FileOutputStream_write, "java.io.FileOutputStream_write$P5cd96296I32Z",
     (void *self, int32_t b, uint8_t append)) {
    (void)append;
    const char c = (char)b;
    jcl_fos_write(self, &c, 1);
}

/* --- java.io.FileInputStream natives (standard input) ---------------- */

/* Reads up to len bytes from the stream's descriptor; only fd 0 (System.in)
 * is open. read()/_read() return what is available, so an interactive line
 * is delivered without waiting for a full buffer. -1 at end of input. */
static int32_t jcl_fis_read(void *self, void *buf, int32_t len) {
    int32_t fd = jcl_fos_fd(self);
    if (fd != 0 && fd <= 2) {
        nabu_throw(jcl_throw("java/io/IOException", "java/lang/Exception", "Stream Closed"));
        return -1;
    }
    if (len == 0) return 0;
#ifdef _WIN32
    static int binary_set = 0;
    if (fd == 0 && !binary_set) {
        /* Raw bytes, as the JDK reads them; BufferedReader handles "\r\n". */
        _setmode(0, _O_BINARY);
        binary_set = 1;
    }
    int n = _read(fd, buf, (unsigned int)len);
#else
    ssize_t n = read(fd, buf, (size_t)len);
#endif
    if (n < 0) {
        nabu_throw(jcl_throw("java/io/IOException", "java/lang/Exception", "Read error"));
        return -1;
    }
    return n == 0 ? -1 : (int32_t)n;
}

BIND(int32_t, jcl_java_io_FileInputStream_readBytes, "java.io.FileInputStream_readBytes",
     (void *self, void *b, int32_t off, int32_t len)) {
    if (b == NULL) { nabu_throw(jcl_throw("java/lang/NullPointerException", "java/lang/RuntimeException", NULL)); return -1; }
    if (off < 0 || len < 0 || off + len > jcl_arr_len(b)) {
        nabu_throw(jcl_throw("java/lang/IndexOutOfBoundsException", "java/lang/RuntimeException", NULL));
        return -1;
    }
    return jcl_fis_read(self, (char *)b + 8 + off, len);
}
BIND(int32_t, jcl_java_io_FileInputStream_read0, "java.io.FileInputStream_read0", (void *self)) {
    unsigned char c;
    int32_t n = jcl_fis_read(self, &c, 1);
    return n == 1 ? (int32_t)c : -1;
}
BIND(int32_t, jcl_java_io_FileInputStream_available0, "java.io.FileInputStream_available0", (void *self)) {
    (void)self;
    return 0;
}
BIND(int64_t, jcl_java_io_FileInputStream_skip0, "java.io.FileInputStream_skip0", (void *self, int64_t n)) {
    char buf[512];
    int64_t skipped = 0;
    while (skipped < n) {
        int32_t chunk = (int32_t)(n - skipped < (int64_t)sizeof buf ? n - skipped : (int64_t)sizeof buf);
        int32_t r = jcl_fis_read(self, buf, chunk);
        if (r <= 0) break;
        skipped += r;
    }
    return skipped;
}

/* --- java.io.File: the natives of the JDK's file system class -------------
 * java.io.File is compiled from the JDK's bytecode; DefaultFileSystem gives
 * a WinNTFileSystem (Windows) or UnixFileSystem, whose natives are here.
 * Paths come in as Java strings (UTF-8 via nabu_cstr); Windows uses the
 * wide-character API. */

#define JCL_BA_EXISTS    0x01
#define JCL_BA_REGULAR   0x02
#define JCL_BA_DIRECTORY 0x04
#define JCL_BA_HIDDEN    0x08

/* File.path of a java.io.File object. */
static const char *jcl_file_path_of(void *file) {
    intptr_t off = file != NULL ? nabu_instance_field_offset(file, "path") : -1;
    if (off < 0) return "";
    void *path = *(void **)((char *)file + off);
    return path != NULL ? nabu_cstr(path) : "";
}

static void *jcl_string_array(int32_t n) {
    void *arr = jcl_arr_ref(n);
    jcl_arr_set_type(arr, "[Ljava/lang/String;");
    return arr;
}

#ifdef _WIN32
static wchar_t *jcl_wide(const char *utf8) {
    int n = MultiByteToWideChar(CP_UTF8, 0, utf8, -1, NULL, 0);
    wchar_t *w = (wchar_t *)jcl_alloc((size_t)(n > 0 ? n : 1) * sizeof(wchar_t));
    if (n > 0) MultiByteToWideChar(CP_UTF8, 0, utf8, -1, w, n);
    return w;
}
static void *jcl_jstring_wide(const wchar_t *w) {
    int n = WideCharToMultiByte(CP_UTF8, 0, w, -1, NULL, 0, NULL, NULL);
    char *u = (char *)jcl_alloc((size_t)(n > 0 ? n : 1));
    if (n > 0) WideCharToMultiByte(CP_UTF8, 0, w, -1, u, n, NULL, NULL);
    return nabu_jstring_new(u);
}
static int32_t jcl_fs_attributes(const char *path) {
    DWORD a = GetFileAttributesW(jcl_wide(path));
    if (a == INVALID_FILE_ATTRIBUTES) return 0;
    int32_t r = JCL_BA_EXISTS;
    r |= (a & FILE_ATTRIBUTE_DIRECTORY) ? JCL_BA_DIRECTORY : JCL_BA_REGULAR;
    if (a & FILE_ATTRIBUTE_HIDDEN) r |= JCL_BA_HIDDEN;
    return r;
}
static int jcl_fs_info(const char *path, WIN32_FILE_ATTRIBUTE_DATA *data) {
    return GetFileAttributesExW(jcl_wide(path), GetFileExInfoStandard, data) != 0;
}
/* FILETIME (100 ns since 1601) <-> milliseconds since 1970. */
static int64_t jcl_filetime_ms(FILETIME t) {
    int64_t v = ((int64_t)t.dwHighDateTime << 32) | t.dwLowDateTime;
    return (v - 116444736000000000LL) / 10000;
}
#define JCL_FS(name) "java.io.WinNTFileSystem_" name
#else
#include <sys/stat.h>
#include <dirent.h>
#include <limits.h>
#include <utime.h>
static int32_t jcl_fs_attributes(const char *path) {
    struct stat st;
    if (stat(path, &st) != 0) return 0;
    int32_t r = JCL_BA_EXISTS;
    r |= S_ISDIR(st.st_mode) ? JCL_BA_DIRECTORY : S_ISREG(st.st_mode) ? JCL_BA_REGULAR : 0;
    const char *base = strrchr(path, '/');
    if ((base != NULL ? base[1] : path[0]) == '.') r |= JCL_BA_HIDDEN;
    return r;
}
#define JCL_FS(name) "java.io.UnixFileSystem_" name
#endif

BIND(int32_t, jcl_fs_getBooleanAttributes0, JCL_FS("getBooleanAttributes0"), (void *self, void *f)) {
    return jcl_fs_attributes(jcl_file_path_of(f));
}

/* access: FileSystem.ACCESS_READ 4, ACCESS_WRITE 2, ACCESS_EXECUTE 1. */
BIND(uint8_t, jcl_fs_checkAccess0, JCL_FS("checkAccess0"), (void *self, void *f, int32_t access)) {
    const char *path = jcl_file_path_of(f);
#ifdef _WIN32
    DWORD a = GetFileAttributesW(jcl_wide(path));
    if (a == INVALID_FILE_ATTRIBUTES) return 0;
    if ((access & 2) && (a & FILE_ATTRIBUTE_READONLY) && !(a & FILE_ATTRIBUTE_DIRECTORY)) return 0;
    return 1;
#else
    int mode = ((access & 4) ? R_OK : 0) | ((access & 2) ? W_OK : 0) | ((access & 1) ? X_OK : 0);
    return access(path, mode) == 0;
#endif
}

BIND(int64_t, jcl_fs_getLastModifiedTime0, JCL_FS("getLastModifiedTime0"), (void *self, void *f)) {
#ifdef _WIN32
    WIN32_FILE_ATTRIBUTE_DATA d;
    return jcl_fs_info(jcl_file_path_of(f), &d) ? jcl_filetime_ms(d.ftLastWriteTime) : 0;
#else
    struct stat st;
    return stat(jcl_file_path_of(f), &st) == 0 ? (int64_t)st.st_mtime * 1000 : 0;
#endif
}

BIND(int64_t, jcl_fs_getLength0, JCL_FS("getLength0"), (void *self, void *f)) {
#ifdef _WIN32
    WIN32_FILE_ATTRIBUTE_DATA d;
    if (!jcl_fs_info(jcl_file_path_of(f), &d) || (d.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY)) return 0;
    return ((int64_t)d.nFileSizeHigh << 32) | d.nFileSizeLow;
#else
    struct stat st;
    return stat(jcl_file_path_of(f), &st) == 0 && S_ISREG(st.st_mode) ? (int64_t)st.st_size : 0;
#endif
}

/* Only the write permission of the owner has an effect (the read-only
 * attribute on Windows); the others succeed, as on Windows in the JDK. */
BIND(uint8_t, jcl_fs_setPermission0, JCL_FS("setPermission0"),
     (void *self, void *f, int32_t access, uint8_t enable, uint8_t ownerOnly)) {
    const char *path = jcl_file_path_of(f);
#ifdef _WIN32
    if (!(access & 2)) return jcl_fs_attributes(path) != 0;
    wchar_t *w = jcl_wide(path);
    DWORD a = GetFileAttributesW(w);
    if (a == INVALID_FILE_ATTRIBUTES) return 0;
    a = enable ? (a & ~FILE_ATTRIBUTE_READONLY) : (a | FILE_ATTRIBUTE_READONLY);
    return SetFileAttributesW(w, a) != 0;
#else
    struct stat st;
    if (stat(path, &st) != 0) return 0;
    mode_t bits = ((access & 4) ? S_IRUSR : 0) | ((access & 2) ? S_IWUSR : 0) | ((access & 1) ? S_IXUSR : 0);
    if (!ownerOnly) bits |= bits >> 3 | bits >> 6;
    return chmod(path, enable ? (st.st_mode | bits) : (st.st_mode & ~bits)) == 0;
#endif
}

BIND(uint8_t, jcl_fs_createFileExclusively0, JCL_FS("createFileExclusively0"), (void *self, void *pathString)) {
    const char *path = nabu_cstr(pathString);
#ifdef _WIN32
    HANDLE h = CreateFileW(jcl_wide(path), GENERIC_READ | GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_WRITE,
            NULL, CREATE_NEW, FILE_ATTRIBUTE_NORMAL, NULL);
    if (h == INVALID_HANDLE_VALUE) {
        if (GetLastError() == ERROR_FILE_EXISTS) return 0;
        nabu_throw(jcl_throw("java/io/IOException", "java/lang/Exception", "Could not create file"));
        return 0;
    }
    CloseHandle(h);
    return 1;
#else
    int fd = open(path, O_RDWR | O_CREAT | O_EXCL, 0666);
    if (fd < 0) {
        if (errno == EEXIST) return 0;
        nabu_throw(jcl_throw("java/io/IOException", "java/lang/Exception", strerror(errno)));
        return 0;
    }
    close(fd);
    return 1;
#endif
}

BIND(void *, jcl_fs_list0, JCL_FS("list0"), (void *self, void *f)) {
    const char *path = jcl_file_path_of(f);
    int32_t count = 0, cap = 16;
    void **names = (void **)jcl_alloc((size_t)cap * sizeof(void *));
#ifdef _WIN32
    size_t len = strlen(path);
    char *pattern = (char *)jcl_alloc(len + 3);
    memcpy(pattern, path, len);
    if (len > 0 && path[len - 1] != '\\' && path[len - 1] != '/') pattern[len++] = '\\';
    pattern[len++] = '*';
    pattern[len] = 0;
    WIN32_FIND_DATAW fd;
    HANDLE h = FindFirstFileW(jcl_wide(pattern), &fd);
    if (h == INVALID_HANDLE_VALUE) return NULL;
    do {
        if (wcscmp(fd.cFileName, L".") == 0 || wcscmp(fd.cFileName, L"..") == 0) continue;
        if (count == cap) {
            void **bigger = (void **)jcl_alloc((size_t)cap * 2 * sizeof(void *));
            memcpy(bigger, names, (size_t)cap * sizeof(void *));
            names = bigger;
            cap *= 2;
        }
        names[count++] = jcl_jstring_wide(fd.cFileName);
    } while (FindNextFileW(h, &fd));
    FindClose(h);
#else
    DIR *dir = opendir(path);
    if (dir == NULL) return NULL;
    struct dirent *e;
    while ((e = readdir(dir)) != NULL) {
        if (strcmp(e->d_name, ".") == 0 || strcmp(e->d_name, "..") == 0) continue;
        if (count == cap) {
            void **bigger = (void **)jcl_alloc((size_t)cap * 2 * sizeof(void *));
            memcpy(bigger, names, (size_t)cap * sizeof(void *));
            names = bigger;
            cap *= 2;
        }
        names[count++] = nabu_jstring_new(e->d_name);
    }
    closedir(dir);
#endif
    void *arr = jcl_string_array(count);
    for (int32_t i = 0; i < count; i++) jcl_arr_set(arr, i, names[i]);
    return arr;
}

BIND(uint8_t, jcl_fs_createDirectory0, JCL_FS("createDirectory0"), (void *self, void *f)) {
#ifdef _WIN32
    return CreateDirectoryW(jcl_wide(jcl_file_path_of(f)), NULL) != 0;
#else
    return mkdir(jcl_file_path_of(f), 0777) == 0;
#endif
}

BIND(uint8_t, jcl_fs_setLastModifiedTime0, JCL_FS("setLastModifiedTime0"), (void *self, void *f, int64_t ms)) {
#ifdef _WIN32
    HANDLE h = CreateFileW(jcl_wide(jcl_file_path_of(f)), FILE_WRITE_ATTRIBUTES,
            FILE_SHARE_READ | FILE_SHARE_WRITE, NULL, OPEN_EXISTING, FILE_FLAG_BACKUP_SEMANTICS, NULL);
    if (h == INVALID_HANDLE_VALUE) return 0;
    int64_t v = ms * 10000 + 116444736000000000LL;
    FILETIME t = { (DWORD)v, (DWORD)(v >> 32) };
    BOOL ok = SetFileTime(h, NULL, NULL, &t);
    CloseHandle(h);
    return ok != 0;
#else
    struct utimbuf times = { (time_t)(ms / 1000), (time_t)(ms / 1000) };
    return utime(jcl_file_path_of(f), &times) == 0;
#endif
}

BIND(uint8_t, jcl_fs_setReadOnly0, JCL_FS("setReadOnly0"), (void *self, void *f)) {
#ifdef _WIN32
    wchar_t *w = jcl_wide(jcl_file_path_of(f));
    DWORD a = GetFileAttributesW(w);
    return a != INVALID_FILE_ATTRIBUTES && SetFileAttributesW(w, a | FILE_ATTRIBUTE_READONLY) != 0;
#else
    struct stat st;
    const char *path = jcl_file_path_of(f);
    return stat(path, &st) == 0 && chmod(path, st.st_mode & ~(S_IWUSR | S_IWGRP | S_IWOTH)) == 0;
#endif
}

#ifdef _WIN32
BIND(uint8_t, jcl_fs_delete0, JCL_FS("delete0"), (void *self, void *f, uint8_t allowReadOnly)) {
    wchar_t *w = jcl_wide(jcl_file_path_of(f));
    DWORD a = GetFileAttributesW(w);
    if (a == INVALID_FILE_ATTRIBUTES) return 0;
    if (a & FILE_ATTRIBUTE_DIRECTORY) return RemoveDirectoryW(w) != 0;
    if ((a & FILE_ATTRIBUTE_READONLY) && allowReadOnly) SetFileAttributesW(w, a & ~FILE_ATTRIBUTE_READONLY);
    return DeleteFileW(w) != 0;
}
#else
BIND(uint8_t, jcl_fs_delete0, JCL_FS("delete0"), (void *self, void *f)) {
    return remove(jcl_file_path_of(f)) == 0;
}
#endif

BIND(uint8_t, jcl_fs_rename0, JCL_FS("rename0"), (void *self, void *from, void *to)) {
#ifdef _WIN32
    return MoveFileW(jcl_wide(jcl_file_path_of(from)), jcl_wide(jcl_file_path_of(to))) != 0;
#else
    return rename(jcl_file_path_of(from), jcl_file_path_of(to)) == 0;
#endif
}

#ifdef _WIN32
/* The final path of an existing file or directory (links resolved, names in
 * their real case) without the \\?\ prefix, or 0 when it does not exist. */
static int jcl_win_final_path(const wchar_t *path, wchar_t *out, DWORD cap) {
    HANDLE h = CreateFileW(path, 0, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, NULL,
                           OPEN_EXISTING, FILE_FLAG_BACKUP_SEMANTICS, NULL);
    if (h == INVALID_HANDLE_VALUE) return 0;
    DWORD n = GetFinalPathNameByHandleW(h, out, cap, VOLUME_NAME_DOS);
    CloseHandle(h);
    if (n == 0 || n >= cap) return 0;
    if (wcsncmp(out, L"\\\\?\\UNC\\", 8) == 0) {
        out[6] = L'\\'; /* \\?\UNC\server -> \\server */
        memmove(out, out + 6, (wcslen(out + 6) + 1) * sizeof(wchar_t));
    } else if (wcsncmp(out, L"\\\\?\\", 4) == 0) {
        memmove(out, out + 4, (wcslen(out + 4) + 1) * sizeof(wchar_t));
    }
    return 1;
}
#endif

/* The absolute, normalized path; links and the case of names are resolved
 * for the part of the path that exists, like the JDK does on Windows. */
BIND(void *, jcl_fs_canonicalize0, JCL_FS("canonicalize0"), (void *self, void *pathString)) {
    const char *path = nabu_cstr(pathString);
#ifdef _WIN32
    static wchar_t buf[32768], real[32768];
    DWORD n = GetFullPathNameW(jcl_wide(path), 32768, buf, NULL);
    if (n == 0 || n >= 32768) return pathString;
    /* Resolve the longest existing prefix; the rest does not exist and stays as it is. */
    size_t len = wcslen(buf);
    while (len > 3) {
        wchar_t saved = buf[len];
        buf[len] = 0;
        int found = jcl_win_final_path(buf, real, 32768);
        buf[len] = saved;
        if (found) {
            size_t rl = wcslen(real);
            if (rl > 0 && real[rl - 1] == L'\\' && buf[len] != 0) real[--rl] = 0;
            if (rl + wcslen(buf + len) + 1 >= 32768) return pathString;
            wcscpy(real + rl, buf + len);
            return jcl_jstring_wide(real);
        }
        while (len > 3 && buf[len - 1] != L'\\') len--;
        if (len > 3) len--; /* drop the separator too */
    }
    return jcl_jstring_wide(buf);
#else
    char buf[PATH_MAX];
    return realpath(path, buf) != NULL ? nabu_jstring_new(buf) : pathString;
#endif
}

BIND(int32_t, jcl_fs_getNameMax0, JCL_FS("getNameMax0"), (void *self, void *pathString)) { return 255; }

BIND(int64_t, jcl_fs_getSpace0, JCL_FS("getSpace0"), (void *self, void *f, int32_t t)) {
#ifdef _WIN32
    ULARGE_INTEGER freeToCaller, total, freeTotal;
    const wchar_t *path = jcl_wide(jcl_file_path_of(f));
    if (!GetDiskFreeSpaceExW(path, &freeToCaller, &total, &freeTotal)) {
        /* a file, not a directory: ask for the volume */
        wchar_t root[4] = { path[0], L':', L'\\', 0 };
        if (path[0] == 0 || path[1] != L':'
                || !GetDiskFreeSpaceExW(root, &freeToCaller, &total, &freeTotal)) return 0;
    }
    return t == 0 ? (int64_t)total.QuadPart : t == 1 ? (int64_t)freeTotal.QuadPart : (int64_t)freeToCaller.QuadPart;
#else
    return 0;
#endif
}

#ifdef _WIN32
BIND(void *, jcl_fs_getFinalPath0, JCL_FS("getFinalPath0"), (void *self, void *pathString)) {
    return jcl_fs_canonicalize0(self, pathString);
}
BIND(int32_t, jcl_fs_listRoots0, JCL_FS("listRoots0"), (void)) { return (int32_t)GetLogicalDrives(); }
/* The current directory of a drive (1 = A:), without the drive letter. */
BIND(void *, jcl_fs_getDriveDirectory, JCL_FS("getDriveDirectory$PI32"), (void *self, int32_t drive)) {
    wchar_t buf[MAX_PATH];
    if (_wgetdcwd(drive, buf, MAX_PATH) == NULL) return NULL;
    const wchar_t *p = buf;
    if (iswalpha(p[0]) && p[1] == L':') p += 2;
    return jcl_jstring_wide(p);
}
#endif

/* --- FileInputStream / FileOutputStream on files ------------------------ */

#ifdef NABU_GC_BOEHM
/* A FileDescriptor nobody references any more: close its descriptor, as the
 * JDK's cleaner does. The descriptor can have been closed already. */
static void jcl_fd_finalize(void *fdObj, void *unused) {
    (void)unused;
    int32_t *slot = jcl_fd_slot(fdObj);
    if (slot != NULL && *slot > 2) {
#ifdef _WIN32
        _close(*slot);
#else
        close(*slot);
#endif
        *slot = -1;
    }
}
#endif

static void jcl_open_file(void *self, void *pathString, int flags) {
    const char *path = nabu_cstr(pathString);
#ifdef _WIN32
    int fd = _wopen(jcl_wide(path), flags | _O_BINARY | _O_NOINHERIT, _S_IREAD | _S_IWRITE);
#else
    int fd = open(path, flags, 0666);
#endif
    if (fd < 0) {
        const char *reason = strerror(errno);
        nabu_throw(jcl_throw("java/io/FileNotFoundException", "java/io/IOException",
                jcl_concat(jcl_concat(jcl_concat(path, " ("), reason), ")")));
        return;
    }
    intptr_t off = nabu_instance_field_offset(self, "fd");
    int32_t *slot = off >= 0 ? jcl_fd_slot(*(void **)((char *)self + off)) : NULL;
    if (slot != NULL) {
        *slot = fd;
#ifdef NABU_GC_BOEHM
        /* The descriptor object refers to its stream (parent), so the two form a cycle:
         * only the unordered variant finalizes those. */
        GC_register_finalizer_no_order(*(void **)((char *)self + off), jcl_fd_finalize, NULL, NULL, NULL);
#endif
    } else {
#ifdef _WIN32
        _close(fd);
#else
        close(fd);
#endif
    }
}
BIND(void, jcl_java_io_FileInputStream_open0, "java.io.FileInputStream_open0", (void *self, void *path)) {
    jcl_open_file(self, path, O_RDONLY);
}
BIND(void, jcl_java_io_FileOutputStream_open0, "java.io.FileOutputStream_open0", (void *self, void *path, uint8_t append)) {
    jcl_open_file(self, path, O_WRONLY | O_CREAT | (append ? O_APPEND : O_TRUNC));
}
BIND(int64_t, jcl_java_io_FileInputStream_length0, "java.io.FileInputStream_length0", (void *self)) {
    int32_t fd = jcl_fos_fd(self);
    if (fd <= 2) return 0;
#ifdef _WIN32
    return _filelengthi64(fd);
#else
    struct stat st;
    return fstat(fd, &st) == 0 ? (int64_t)st.st_size : 0;
#endif
}
BIND(int64_t, jcl_java_io_FileInputStream_position0, "java.io.FileInputStream_position0", (void *self)) {
    int32_t fd = jcl_fos_fd(self);
    if (fd <= 2) return 0;
#ifdef _WIN32
    return _telli64(fd);
#else
    return (int64_t)lseek(fd, 0, SEEK_CUR);
#endif
}
/* --- sun.nio.ch.FileDispatcherImpl: FileChannel on a file ----------------
 * The channel works on the CRT descriptor in the FileDescriptor, like the streams. IOStatus values: EOF -1,
 * UNSUPPORTED_CASE -6 (the JDK then takes another way). */

static int jcl_chan_fd(void *fdObj) {
    int32_t *slot = jcl_fd_slot(fdObj);
    return slot != NULL ? *slot : -1;
}
static void jcl_chan_error(const char *what) {
    char reason[96];
    snprintf(reason, sizeof reason, "%s failed (%s)", what, strerror(errno));
    nabu_throw(jcl_throw("java/io/IOException", "java/lang/Exception", jcl_dup(reason)));
}
#ifdef _WIN32
#define JCL_READ(fd, buf, n) _read((fd), (buf), (unsigned int)(n))
#define JCL_WRITE(fd, buf, n) _write((fd), (buf), (unsigned int)(n))
#define JCL_SEEK(fd, off, whence) _lseeki64((fd), (off), (whence))
#else
#define JCL_READ(fd, buf, n) read((fd), (buf), (size_t)(n))
#define JCL_WRITE(fd, buf, n) write((fd), (buf), (size_t)(n))
#define JCL_SEEK(fd, off, whence) lseek((fd), (off_t)(off), (whence))
#endif

BIND(int32_t, jcl_FileDispatcherImpl_read0, "sun.nio.ch.FileDispatcherImpl_read0", (void *fdObj, int64_t address, int32_t len)) {
    int fd = jcl_chan_fd(fdObj);
    if (fd < 0) { nabu_throw(jcl_throw("java/io/IOException", "java/lang/Exception", "Stream Closed")); return -1; }
    int n = (int)JCL_READ(fd, (void *)(intptr_t)address, len);
    if (n < 0) { jcl_chan_error("Read"); return -1; }
    return n == 0 && len > 0 ? -1 : n;
}
BIND(int32_t, jcl_FileDispatcherImpl_pread0, "sun.nio.ch.FileDispatcherImpl_pread0",
     (void *fdObj, int64_t address, int32_t len, int64_t position)) {
    int fd = jcl_chan_fd(fdObj);
    if (fd < 0) { nabu_throw(jcl_throw("java/io/IOException", "java/lang/Exception", "Stream Closed")); return -1; }
    int64_t saved = JCL_SEEK(fd, 0, SEEK_CUR);
    if (JCL_SEEK(fd, position, SEEK_SET) < 0) { jcl_chan_error("Seek"); return -1; }
    int n = (int)JCL_READ(fd, (void *)(intptr_t)address, len);
    JCL_SEEK(fd, saved, SEEK_SET);
    if (n < 0) { jcl_chan_error("Read"); return -1; }
    return n == 0 && len > 0 ? -1 : n;
}
BIND(int32_t, jcl_FileDispatcherImpl_write0, "sun.nio.ch.FileDispatcherImpl_write0",
     (void *fdObj, int64_t address, int32_t len, uint8_t append)) {
    (void)append;
    int fd = jcl_chan_fd(fdObj);
    if (fd < 0) { nabu_throw(jcl_throw("java/io/IOException", "java/lang/Exception", "Stream Closed")); return -1; }
    int n = (int)JCL_WRITE(fd, (const void *)(intptr_t)address, len);
    if (n < 0) { jcl_chan_error("Write"); return -1; }
    return n;
}
BIND(int32_t, jcl_FileDispatcherImpl_pwrite0, "sun.nio.ch.FileDispatcherImpl_pwrite0",
     (void *fdObj, int64_t address, int32_t len, int64_t position)) {
    int fd = jcl_chan_fd(fdObj);
    if (fd < 0) { nabu_throw(jcl_throw("java/io/IOException", "java/lang/Exception", "Stream Closed")); return -1; }
    int64_t saved = JCL_SEEK(fd, 0, SEEK_CUR);
    if (JCL_SEEK(fd, position, SEEK_SET) < 0) { jcl_chan_error("Seek"); return -1; }
    int n = (int)JCL_WRITE(fd, (const void *)(intptr_t)address, len);
    JCL_SEEK(fd, saved, SEEK_SET);
    if (n < 0) { jcl_chan_error("Write"); return -1; }
    return n;
}
BIND(int64_t, jcl_FileDispatcherImpl_readv0, "sun.nio.ch.FileDispatcherImpl_readv0", (void *fdObj, int64_t address, int32_t len)) {
    (void)fdObj; (void)address; (void)len;
    return -6;
}
BIND(int64_t, jcl_FileDispatcherImpl_writev0, "sun.nio.ch.FileDispatcherImpl_writev0",
     (void *fdObj, int64_t address, int32_t len, uint8_t append)) {
    (void)fdObj; (void)address; (void)len; (void)append;
    return -6;
}
/* offset -1: the current position; otherwise set it. Returns the position. */
BIND(int64_t, jcl_FileDispatcherImpl_seek0, "sun.nio.ch.FileDispatcherImpl_seek0", (void *fdObj, int64_t offset)) {
    int fd = jcl_chan_fd(fdObj);
    if (fd < 0) { nabu_throw(jcl_throw("java/io/IOException", "java/lang/Exception", "Stream Closed")); return -1; }
    int64_t r = offset < 0 ? JCL_SEEK(fd, 0, SEEK_CUR) : JCL_SEEK(fd, offset, SEEK_SET);
    if (r < 0) { jcl_chan_error("Seek"); return -1; }
    return r;
}
BIND(int32_t, jcl_FileDispatcherImpl_force0, "sun.nio.ch.FileDispatcherImpl_force0", (void *fdObj, uint8_t metaData)) {
    (void)metaData;
    int fd = jcl_chan_fd(fdObj);
#ifdef _WIN32
    if (fd >= 0) _commit(fd);
#else
    if (fd >= 0) fsync(fd);
#endif
    return 0;
}
BIND(int32_t, jcl_FileDispatcherImpl_truncate0, "sun.nio.ch.FileDispatcherImpl_truncate0", (void *fdObj, int64_t size)) {
    int fd = jcl_chan_fd(fdObj);
    if (fd < 0) { nabu_throw(jcl_throw("java/io/IOException", "java/lang/Exception", "Stream Closed")); return -1; }
#ifdef _WIN32
    int bad = _chsize_s(fd, size) != 0;
#else
    int bad = ftruncate(fd, (off_t)size) != 0;
#endif
    if (bad) { jcl_chan_error("Truncate"); return -1; }
    return 0;
}
BIND(int64_t, jcl_FileDispatcherImpl_size0, "sun.nio.ch.FileDispatcherImpl_size0", (void *fdObj)) {
    int fd = jcl_chan_fd(fdObj);
    if (fd < 0) { nabu_throw(jcl_throw("java/io/IOException", "java/lang/Exception", "Stream Closed")); return -1; }
#ifdef _WIN32
    int64_t n = _filelengthi64(fd);
#else
    struct stat st;
    int64_t n = fstat(fd, &st) == 0 ? (int64_t)st.st_size : -1;
#endif
    if (n < 0) { jcl_chan_error("Size"); return -1; }
    return n;
}
BIND(int32_t, jcl_FileDispatcherImpl_available0, "sun.nio.ch.FileDispatcherImpl_available0", (void *fdObj)) {
    int fd = jcl_chan_fd(fdObj);
    if (fd < 0) return 0;
    int64_t pos = JCL_SEEK(fd, 0, SEEK_CUR);
#ifdef _WIN32
    int64_t size = _filelengthi64(fd);
#else
    struct stat st;
    int64_t size = fstat(fd, &st) == 0 ? (int64_t)st.st_size : 0;
#endif
    int64_t left = size - pos;
    return left < 0 ? 0 : left > 0x7fffffff ? 0x7fffffff : (int32_t)left;
}
BIND(uint8_t, jcl_FileDispatcherImpl_isOther0, "sun.nio.ch.FileDispatcherImpl_isOther0", (void *fdObj)) { (void)fdObj; return 0; }
/* No file locks between processes: the lock is always granted (0 = LOCKED). */
BIND(int32_t, jcl_FileDispatcherImpl_lock0, "sun.nio.ch.FileDispatcherImpl_lock0",
     (void *fdObj, uint8_t blocking, int64_t pos, int64_t size, uint8_t shared)) {
    (void)fdObj; (void)blocking; (void)pos; (void)size; (void)shared;
    return 0;
}
BIND(void, jcl_FileDispatcherImpl_release0, "sun.nio.ch.FileDispatcherImpl_release0", (void *fdObj, int64_t pos, int64_t size)) {
    (void)fdObj; (void)pos; (void)size;
}
BIND(int32_t, jcl_FileDispatcherImpl_maxDirectTransferSize0, "sun.nio.ch.FileDispatcherImpl_maxDirectTransferSize0", (void)) {
    return 0x7fffffff;
}
BIND(int64_t, jcl_FileDispatcherImpl_transferTo0, "sun.nio.ch.FileDispatcherImpl_transferTo0",
     (void *src, int64_t position, int64_t count, void *dst, uint8_t append)) {
    (void)src; (void)position; (void)count; (void)dst; (void)append;
    return -6; /* UNSUPPORTED_CASE: the JDK copies through a buffer */
}
BIND(int64_t, jcl_FileDispatcherImpl_allocationGranularity0, "sun.nio.ch.FileDispatcherImpl_allocationGranularity0", (void)) {
    return 65536;
}
BIND(int64_t, jcl_FileDispatcherImpl_map0, "sun.nio.ch.FileDispatcherImpl_map0",
     (void *fdObj, int32_t prot, int64_t position, int64_t length, uint8_t isSync)) {
    (void)fdObj; (void)prot; (void)position; (void)length; (void)isSync;
    nabu_throw(jcl_throw("java/io/IOException", "java/lang/Exception",
            "Memory-mapped files are not supported in a native executable"));
    return 0;
}
BIND(int32_t, jcl_FileDispatcherImpl_unmap0, "sun.nio.ch.FileDispatcherImpl_unmap0", (int64_t address, int64_t length)) {
    (void)address; (void)length;
    return 0;
}
BIND(int64_t, jcl_FileDispatcherImpl_duplicateHandle, "sun.nio.ch.FileDispatcherImpl_duplicateHandle", (int64_t handle)) {
    return handle;
}
BIND(int32_t, jcl_FileDispatcherImpl_setDirect0, "sun.nio.ch.FileDispatcherImpl_setDirect0", (void *fdObj, void *buf)) {
    (void)fdObj; (void)buf;
    return -1;
}
BIND(int32_t, jcl_IOUtil_iovMax, "sun.nio.ch.IOUtil_iovMax", (void)) { return 16; }
BIND(int64_t, jcl_IOUtil_writevMax, "sun.nio.ch.IOUtil_writevMax", (void)) { return 0x7fffffffLL; }

/* --- NativeProcess: child processes (Windows) --------------------------- */

#ifdef _WIN32
static HANDLE jcl_process_handle(int64_t h) { return (HANDLE)(intptr_t)h; }

/* The pipe end of the parent as a CRT descriptor, or -1. */
static int64_t jcl_pipe_fd(HANDLE h, int flags) {
    if (h == NULL) return -1;
    int fd = _open_osfhandle((intptr_t)h, flags | _O_BINARY | _O_NOINHERIT);
    if (fd < 0) { CloseHandle(h); return -1; }
    return fd;
}
#endif

BIND(int64_t, jcl_NativeProcess_spawn0, "io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime.NativeProcess_spawn0",
     (void *cmd, void *env, void *dir, void *modesArr, void *fdsArr)) {
    int64_t *fds = (int64_t *)((char *)fdsArr + 8);
    fds[0] = fds[1] = fds[2] = -1;
#ifdef _WIN32
    const int32_t *m = (const int32_t *)((const char *)modesArr + 8);
    SECURITY_ATTRIBUTES sa = {sizeof sa, NULL, TRUE};
    HANDLE inR = NULL, inW = NULL, outR = NULL, outW = NULL, errR = NULL, errW = NULL;
    STARTUPINFOW si;
    PROCESS_INFORMATION pi;
    memset(&si, 0, sizeof si);
    memset(&pi, 0, sizeof pi);
    si.cb = sizeof si;
    si.dwFlags = STARTF_USESTDHANDLES;

    if (m[0] == 0) {
        if (!CreatePipe(&inR, &inW, &sa, 0)) goto fail;
        SetHandleInformation(inW, HANDLE_FLAG_INHERIT, 0);
        si.hStdInput = inR;
    } else {
        si.hStdInput = GetStdHandle(STD_INPUT_HANDLE);
    }
    if (m[1] == 0) {
        if (!CreatePipe(&outR, &outW, &sa, 0)) goto fail;
        SetHandleInformation(outR, HANDLE_FLAG_INHERIT, 0);
        si.hStdOutput = outW;
    } else {
        si.hStdOutput = GetStdHandle(STD_OUTPUT_HANDLE);
    }
    if (m[3] != 0) {
        /* redirectErrorStream: the error output goes where the standard output goes */
        si.hStdError = si.hStdOutput;
    } else if (m[2] == 0) {
        if (!CreatePipe(&errR, &errW, &sa, 0)) goto fail;
        SetHandleInformation(errR, HANDLE_FLAG_INHERIT, 0);
        si.hStdError = errW;
    } else {
        si.hStdError = GetStdHandle(STD_ERROR_HANDLE);
    }

    wchar_t *wcmd = _wcsdup(jcl_wide(nabu_cstr(cmd)));
    wchar_t *wdir = dir != NULL ? _wcsdup(jcl_wide(nabu_cstr(dir))) : NULL;
    wchar_t *block = NULL;
    if (env != NULL) {
        int32_t n = jcl_arr_len(env);
        size_t total = 1;
        wchar_t **parts = (wchar_t **)calloc((size_t)n + 1, sizeof(wchar_t *));
        for (int32_t i = 0; i < n; i++) {
            parts[i] = _wcsdup(jcl_wide(nabu_cstr(((void **)((char *)env + 8))[i])));
            total += wcslen(parts[i]) + 1;
        }
        block = (wchar_t *)calloc(total + 1, sizeof(wchar_t));
        size_t at = 0;
        for (int32_t i = 0; i < n; i++) {
            size_t len = wcslen(parts[i]);
            memcpy(block + at, parts[i], len * sizeof(wchar_t));
            at += len + 1;
            free(parts[i]);
        }
        free(parts);
    }
    BOOL ok = CreateProcessW(NULL, wcmd, NULL, NULL, TRUE, CREATE_UNICODE_ENVIRONMENT, block, wdir, &si, &pi);
    DWORD error = ok ? 0 : GetLastError();
    free(wcmd);
    free(wdir);
    free(block);
    if (!ok) {
        char reason[160];
        snprintf(reason, sizeof reason, "Cannot run program \"%s\": error=%lu", nabu_cstr(cmd), (unsigned long)error);
        if (inR) CloseHandle(inR);
        if (inW) CloseHandle(inW);
        if (outR) CloseHandle(outR);
        if (outW) CloseHandle(outW);
        if (errR) CloseHandle(errR);
        if (errW) CloseHandle(errW);
        nabu_throw(jcl_throw("java/io/IOException", "java/lang/Exception", jcl_dup(reason)));
        return 0;
    }
    /* The child has its ends; the parent keeps the others. */
    if (inR) CloseHandle(inR);
    if (outW) CloseHandle(outW);
    if (errW) CloseHandle(errW);
    CloseHandle(pi.hThread);
    fds[0] = jcl_pipe_fd(inW, 0);
    fds[1] = jcl_pipe_fd(outR, _O_RDONLY);
    fds[2] = jcl_pipe_fd(errR, _O_RDONLY);
    return (int64_t)(intptr_t)pi.hProcess;
fail:
    if (inR) CloseHandle(inR);
    if (inW) CloseHandle(inW);
    if (outR) CloseHandle(outR);
    if (outW) CloseHandle(outW);
    if (errR) CloseHandle(errR);
    if (errW) CloseHandle(errW);
    nabu_throw(jcl_throw("java/io/IOException", "java/lang/Exception", "Cannot create a pipe"));
    return 0;
#else
    (void)cmd; (void)env; (void)dir; (void)modesArr;
    nabu_throw(jcl_throw("java/io/IOException", "java/lang/Exception",
            "Starting a process is only supported on Windows in a native executable"));
    return 0;
#endif
}

/* true when the process has ended (within millis; negative: wait without limit) */
BIND(uint8_t, jcl_NativeProcess_wait0, "io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime.NativeProcess_wait0", (int64_t handle, int64_t millis)) {
#ifdef _WIN32
    return WaitForSingleObject(jcl_process_handle(handle), millis < 0 ? INFINITE : (DWORD)millis) == WAIT_OBJECT_0;
#else
    (void)handle; (void)millis;
    return 1;
#endif
}
BIND(int32_t, jcl_NativeProcess_exit0, "io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime.NativeProcess_exit0", (int64_t handle)) {
#ifdef _WIN32
    DWORD code = 0;
    if (!GetExitCodeProcess(jcl_process_handle(handle), &code)) return -1;
    return (int32_t)code;
#else
    (void)handle;
    return -1;
#endif
}
BIND(void, jcl_NativeProcess_destroy0, "io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime.NativeProcess_destroy0", (int64_t handle)) {
#ifdef _WIN32
    TerminateProcess(jcl_process_handle(handle), 1);
#else
    (void)handle;
#endif
}
BIND(int32_t, jcl_NativeProcess_pid0, "io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime.NativeProcess_pid0", (int64_t handle)) {
#ifdef _WIN32
    return (int32_t)GetProcessId(jcl_process_handle(handle));
#else
    (void)handle;
    return -1;
#endif
}
BIND(int32_t, jcl_NativeProcess_read0, "io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime.NativeProcess_read0", (int32_t fd, void *b, int32_t off, int32_t len)) {
    if (b == NULL || off < 0 || len < 0 || off + len > jcl_arr_len(b)) {
        nabu_throw(jcl_throw("java/lang/IndexOutOfBoundsException", "java/lang/RuntimeException", NULL));
        return -1;
    }
#ifdef _WIN32
    int n = _read(fd, (char *)b + 8 + off, (unsigned int)len);
#else
    ssize_t n = read(fd, (char *)b + 8 + off, (size_t)len);
#endif
    if (n < 0) {
        /* a pipe whose write end is closed reports a broken pipe, which is the end of the output */
        return -1;
    }
    return n == 0 ? -1 : (int32_t)n;
}
BIND(void, jcl_NativeProcess_write0, "io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime.NativeProcess_write0", (int32_t fd, void *b, int32_t off, int32_t len)) {
    if (b == NULL || off < 0 || len < 0 || off + len > jcl_arr_len(b)) {
        nabu_throw(jcl_throw("java/lang/IndexOutOfBoundsException", "java/lang/RuntimeException", NULL));
        return;
    }
    const char *p = (const char *)b + 8 + off;
    while (len > 0) {
#ifdef _WIN32
        int n = _write(fd, p, (unsigned int)len);
#else
        ssize_t n = write(fd, p, (size_t)len);
#endif
        if (n <= 0) {
            nabu_throw(jcl_throw("java/io/IOException", "java/lang/Exception", "Broken pipe"));
            return;
        }
        p += n;
        len -= (int32_t)n;
    }
}
BIND(void, jcl_NativeProcess_close0, "io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime.NativeProcess_close0", (int32_t fd)) {
#ifdef _WIN32
    _close(fd);
#else
    close(fd);
#endif
}

/* --- java.io.RandomAccessFile ------------------------------------------- */

/* The CRT descriptor of a RandomAccessFile (this.fd.fd), as for the streams. */
BIND(void, jcl_java_io_RandomAccessFile_open0, "java.io.RandomAccessFile_open0", (void *self, void *path, int32_t mode)) {
    /* RandomAccessFile.O_RDONLY = 1, O_RDWR = 2 */
    jcl_open_file(self, path, (mode & 2) ? (O_RDWR | O_CREAT) : O_RDONLY);
}
BIND(int32_t, jcl_java_io_RandomAccessFile_read0, "java.io.RandomAccessFile_read0", (void *self)) {
    unsigned char c;
    int32_t n = jcl_fis_read(self, &c, 1);
    return n == 1 ? (int32_t)c : -1;
}
BIND(int32_t, jcl_java_io_RandomAccessFile_readBytes0, "java.io.RandomAccessFile_readBytes0",
     (void *self, void *b, int32_t off, int32_t len)) {
    if (b == NULL) { nabu_throw(jcl_throw("java/lang/NullPointerException", "java/lang/RuntimeException", NULL)); return -1; }
    if (off < 0 || len < 0 || off + len > jcl_arr_len(b)) {
        nabu_throw(jcl_throw("java/lang/IndexOutOfBoundsException", "java/lang/RuntimeException", NULL));
        return -1;
    }
    return jcl_fis_read(self, (char *)b + 8 + off, len);
}
BIND(void, jcl_java_io_RandomAccessFile_write0, "java.io.RandomAccessFile_write0", (void *self, int32_t b)) {
    const char c = (char)b;
    jcl_fos_write(self, &c, 1);
}
BIND(void, jcl_java_io_RandomAccessFile_writeBytes0, "java.io.RandomAccessFile_writeBytes0",
     (void *self, void *b, int32_t off, int32_t len)) {
    if (b == NULL) { nabu_throw(jcl_throw("java/lang/NullPointerException", "java/lang/RuntimeException", NULL)); return; }
    if (off < 0 || len < 0 || off + len > jcl_arr_len(b)) {
        nabu_throw(jcl_throw("java/lang/IndexOutOfBoundsException", "java/lang/RuntimeException", NULL));
        return;
    }
    jcl_fos_write(self, (const char *)b + 8 + off, (size_t)len);
}
BIND(int64_t, jcl_java_io_RandomAccessFile_getFilePointer, "java.io.RandomAccessFile_getFilePointer", (void *self)) {
    return jcl_java_io_FileInputStream_position0(self);
}
BIND(void, jcl_java_io_RandomAccessFile_seek0, "java.io.RandomAccessFile_seek0", (void *self, int64_t pos)) {
    int32_t fd = jcl_fos_fd(self);
    if (fd <= 2) {
        nabu_throw(jcl_throw("java/io/IOException", "java/lang/Exception", "Stream Closed"));
        return;
    }
#ifdef _WIN32
    int64_t r = _lseeki64(fd, pos, SEEK_SET);
#else
    int64_t r = (int64_t)lseek(fd, (off_t)pos, SEEK_SET);
#endif
    if (r < 0) nabu_throw(jcl_throw("java/io/IOException", "java/lang/Exception", "Seek failed"));
}
BIND(int64_t, jcl_java_io_RandomAccessFile_length0, "java.io.RandomAccessFile_length0", (void *self)) {
    return jcl_java_io_FileInputStream_length0(self);
}
BIND(void, jcl_java_io_RandomAccessFile_setLength0, "java.io.RandomAccessFile_setLength0", (void *self, int64_t len)) {
    int32_t fd = jcl_fos_fd(self);
    if (fd <= 2) {
        nabu_throw(jcl_throw("java/io/IOException", "java/lang/Exception", "Stream Closed"));
        return;
    }
#ifdef _WIN32
    int bad = _chsize_s(fd, len) != 0;
#else
    int bad = ftruncate(fd, (off_t)len) != 0;
#endif
    if (bad) nabu_throw(jcl_throw("java/io/IOException", "java/lang/Exception", "setLength failed"));
}

BIND(uint8_t, jcl_java_io_FileInputStream_isRegularFile0, "java.io.FileInputStream_isRegularFile0", (void *self, void *fdObj)) {
    int32_t *slot = jcl_fd_slot(fdObj);
    return slot != NULL && *slot > 2;
}

BIND(void *, jcl_java_lang_System_getProperties, "java.lang.System_getProperties", (void)) {
    if (nabu_properties_meta.type != NULL && nabu_properties_meta.init != NULL
            && nabu_properties_meta.set_property != NULL && nabu_properties_meta.size > 0) {
        return jcl_compiled_properties();
    }
    return jcl_props_table();
}
static void * jcl_java_lang_System_getProperty (void *key) {
    void *p = jcl_props_table();
    void *v = jcl_map_get(p, key);
    return v;
}
BIND(void *, jcl_java_lang_System_getProperty_jstr, "java.lang.System_getProperty", (void *key)) {
    void *r = (void *)jcl_java_lang_System_getProperty(key);
    return r == NULL ? NULL : nabu_jstring_new(nabu_cstr(r));
}
static void * jcl_java_lang_System_getProperty_P523beb0aP523beb0a (void *key, void *dflt) {
    void *v = jcl_java_lang_System_getProperty(key);
    return v != NULL ? v : dflt;
}
BIND(void *, jcl_java_lang_System_getProperty_P523beb0aP523beb0a_jstr, "java.lang.System_getProperty$P523beb0aP523beb0a", (void *key, void *dflt)) {
    void *r = (void *)jcl_java_lang_System_getProperty_P523beb0aP523beb0a(key, dflt);
    return r == NULL ? NULL : nabu_jstring_new(nabu_cstr(r));
}


/* ShutdownSupport.targetOf0(Thread): the Runnable a Thread object was made with, or null. */
BIND(void *, jcl_ShutdownSupport_targetOf0, "io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime.ShutdownSupport_targetOf0", (void *thread)) {
    s_thread *st = (s_thread *)jcl_state_kind(thread, K_THREAD);
    return st != NULL ? st->target : NULL;
}


static void *jcl_new_obj(const char *type, void *state) {
    void *obj = jcl_alloc(48 + 8);
    nabu_init_object(obj, type);
    if (state != NULL) nabu_jcl_set_state(obj, state);
    return obj;
}

static s_map *jcl_map_of(void *self) {
    s_map *m = (s_map *)jcl_state_kind(self, K_MAP);
    if (m == NULL) {
        m = (s_map *)jcl_alloc(sizeof(s_map));
        m->kind = K_MAP;
        m->cap = 8;
        m->ks = (void **)jcl_alloc(sizeof(void *) * 8);
        m->vs = (void **)jcl_alloc(sizeof(void *) * 8);
        if (self != NULL && nabu_jcl_is_object(self)) nabu_jcl_set_state(self, m);
    }
    return m;
}
static void jcl_map_put(void *map, void *k, void *v) {
    s_map *m = jcl_map_of(map);
    for (int32_t i = 0; i < m->size; i++) {
        if (jcl_eq(m->ks[i], k)) { m->vs[i] = v; return; }
    }
    if (m->size == m->cap) {
        m->cap *= 2;
        m->ks = (void **)JCL_REALLOC(m->ks, sizeof(void *) * (size_t)m->cap);
        m->vs = (void **)JCL_REALLOC(m->vs, sizeof(void *) * (size_t)m->cap);
    }
    m->ks[m->size] = k;
    m->vs[m->size] = v;
    m->size++;
}
static void *jcl_map_get(void *map, void *k) {
    s_map *m = (s_map *)jcl_state_kind(map, K_MAP);
    if (m == NULL) return NULL;
    for (int32_t i = 0; i < m->size; i++) if (jcl_eq(m->ks[i], k)) return m->vs[i];
    return NULL;
}









static const char *jcl_desc_name(const char *desc) {
    if (desc == NULL) return "";
    if (desc[0] == 'L' && desc[strlen(desc) - 1] == ';') {
        static char buf[256];
        size_t n = strlen(desc) - 2;
        if (n >= sizeof(buf)) n = sizeof(buf) - 1;
        memcpy(buf, desc + 1, n);
        buf[n] = 0;
        return buf;
    }
    if (desc[0] == '[') return desc;
    switch (desc[0]) {
        case 'I': return "int";
        case 'J': return "long";
        case 'Z': return "boolean";
        case 'D': return "double";
        case 'F': return "float";
        case 'S': return "short";
        case 'B': return "byte";
        case 'C': return "char";
        case 'V': return "void";
        default: return desc;
    }
}

static void *jcl_ann_obj(const nabu_annotation_entry *e) {
    s_ann *st = (s_ann *)jcl_alloc(sizeof(s_ann));
    st->kind = K_ANN;
    st->e = e;
    return jcl_new_obj(e->type_name, st);
}

static void *jcl_field_obj(const char *cls, const nabu_field_info *f) {
    s_field *st = (s_field *)jcl_alloc(sizeof(s_field));
    st->kind = K_FIELD;
    st->f = f;
    st->cls = jcl_dup(cls);
    return jcl_new_obj("java/lang/reflect/Field", st);
}

static s_field *jcl_field_of(void *self) { return (s_field *)jcl_state_kind(self, K_FIELD); }

/* Class.forName kent alleen klassen die het programma heeft: gecompileerd
 * (type-tabel), in de reflectie-registry, of een array. Voor de rest gooit
 * hij ClassNotFoundException, zoals de JVM. Eerder gaf hij voor elke naam een
 * Class terug; picocli's probe op org.fusesource.jansi.AnsiConsole kreeg dan
 * een Class zonder velden, getField gaf null en out.get(null) crashte. */
BIND(void *, jcl_java_lang_Class_forName, "java.lang.Class_forName", (void *name)) {
    const char *n = jcl_str(name);
    char internal[512];
    size_t len = strlen(n);
    if (len == 0 || len >= sizeof internal) {
        nabu_throw(jcl_throw("java/lang/ClassNotFoundException", "java/lang/ReflectiveOperationException", n));
        return NULL;
    }
    for (size_t i = 0; i <= len; i++) internal[i] = n[i] == '.' ? '/' : n[i];
    if (internal[0] != '[' && nabu_compiled_type_for(internal) == NULL
            && nabu_lookup_reflection(internal) == NULL) {
        nabu_throw(jcl_throw("java/lang/ClassNotFoundException", "java/lang/ReflectiveOperationException", n));
        return NULL;
    }
    return jcl_class(internal);
}
BIND(void *, jcl_java_lang_Class_forName_s, "java.lang.Class_forName$P523beb0a", (void *name)) { return jcl_java_lang_Class_forName(name); }
/* Class.forName(String, boolean, ClassLoader): there is one class loader; initialization is lazy anyway. */
BIND(void *, jcl_java_lang_Class_forName_3, "java.lang.Class_forName$P523beb0aZP2f7ad930", (void *name, uint8_t init, void *loader)) {
    (void)init; (void)loader;
    return jcl_java_lang_Class_forName(name);
}
/* Class.forName(Module, String): als forName(String), maar null i.p.v.
 * ClassNotFoundException voor een onbekende klasse (JDK-contract). */
BIND(void *, jcl_java_lang_Class_forName_module, "java.lang.Class_forName$Pb8e700fP523beb0a", (void *module, void *name)) {
    (void)module;
    const char *n = jcl_str(name);
    char internal[512];
    size_t len = strlen(n);
    if (len == 0 || len >= sizeof internal) return NULL;
    for (size_t i = 0; i <= len; i++) internal[i] = n[i] == '.' ? '/' : n[i];
    if (internal[0] != '[' && nabu_compiled_type_for(internal) == NULL
            && nabu_lookup_reflection(internal) == NULL) {
        return NULL;
    }
    return jcl_class(internal);
}
/* De runtime bewaart klassen onder hun interne naam ("java/lang/Integer",
 * "[Ljava/lang/String;"); Class.getName() geeft de binaire naam met punten
 * ("java.lang.Integer", "[Ljava.lang.String;"). */
static char *jcl_binary_name(const char *n) {
    char *r = jcl_dup(n);
    for (char *c = r; *c != '\0'; c++) if (*c == '/') *c = '.';
    return r;
}
/* Class.getTypeName()/getCanonicalName(): arrays als "java.lang.String[]";
 * getCanonicalName maakt van '$' (geneste klasse) bovendien een punt. */
static char *jcl_source_name(const char *n, int nested_dots) {
    int dims = 0;
    while (n[dims] == '[') dims++;
    const char *elem = n + dims;
    char *base;
    if (dims == 0) {
        base = jcl_binary_name(elem);
    } else if (elem[0] == 'L') {
        base = jcl_binary_name(elem + 1);
        size_t bl = strlen(base);
        if (bl > 0 && base[bl - 1] == ';') base[bl - 1] = '\0';
    } else {
        const char *prim = elem[0] == 'Z' ? "boolean" : elem[0] == 'B' ? "byte"
                : elem[0] == 'C' ? "char" : elem[0] == 'S' ? "short"
                : elem[0] == 'I' ? "int" : elem[0] == 'J' ? "long"
                : elem[0] == 'F' ? "float" : elem[0] == 'D' ? "double" : elem;
        base = jcl_dup(prim);
    }
    if (nested_dots) {
        for (char *c = base; *c != '\0'; c++) if (*c == '$') *c = '.';
    }
    for (int i = 0; i < dims; i++) base = jcl_concat(base, "[]");
    return base;
}
static void * jcl_java_lang_Class_getName (void *self) { return (void *)jcl_binary_name(jcl_str(self)); }
BIND(void *, jcl_java_lang_Class_getName_jstr, "java.lang.Class_getName", (void *self)) {
    void *r = (void *)jcl_java_lang_Class_getName(self);
    return r == NULL ? NULL : nabu_jstring_new(nabu_cstr(r));
}
static void * jcl_java_lang_Class_getCanonicalName (void *self) { return (void *)jcl_source_name(jcl_str(self), 1); }
BIND(void *, jcl_java_lang_Class_getCanonicalName_jstr, "java.lang.Class_getCanonicalName", (void *self)) {
    void *r = (void *)jcl_java_lang_Class_getCanonicalName(self);
    return r == NULL ? NULL : nabu_jstring_new(nabu_cstr(r));
}
void *jcl_java_lang_Class_getComponentType(void *self) __asm__("java.lang.Class_getComponentType");
static void * jcl_java_lang_Class_getSimpleName (void *self) {
    const char *n = jcl_str(self);
    if (n[0] == '[' && n[1] != '\0') {
        /* "[Ljava/lang/String;" -> "String[]" (via het componenttype). */
        const char *component = (const char *)jcl_java_lang_Class_getSimpleName(
                jcl_java_lang_Class_getComponentType(self));
        return (void *)jcl_concat(component, "[]");
    }
    const char *slash = strrchr(n, '/');
    if (slash != NULL) n = slash + 1;
    const char *dollar = strrchr(n, '$');
    if (dollar != NULL) n = dollar + 1;
    return (void *)jcl_dup(n);
}
BIND(void *, jcl_java_lang_Class_getSimpleName_jstr, "java.lang.Class_getSimpleName", (void *self)) {
    void *r = (void *)jcl_java_lang_Class_getSimpleName(self);
    return r == NULL ? NULL : nabu_jstring_new(nabu_cstr(r));
}
/* Class.getPackageName(): "sun/text/resources/X" -> "sun.text.resources";
 * een array of primitief: "java.lang" resp. "" (JDK: van het element-type,
 * hier eenvoudig gehouden). */
BIND(void *, jcl_java_lang_Class_getPackageName, "java.lang.Class_getPackageName", (void *self)) {
    const char *n = jcl_str(self);
    const char *slash = strrchr(n, '/');
    if (n[0] == '[' || slash == NULL) return nabu_jstring_new(n[0] == '[' ? "java.lang" : "");
    size_t len = (size_t)(slash - n);
    char *pkg = (char *)jcl_alloc(len + 1);
    for (size_t i = 0; i < len; i++) pkg[i] = n[i] == '/' ? '.' : n[i];
    pkg[len] = '\0';
    return nabu_jstring_new(pkg);
}
BIND(void *, jcl_java_lang_Class_cast, "java.lang.Class_cast", (void *self, void *o)) { (void)self; return o; }
BIND(void *, jcl_java_lang_Class_getAnnotation, "java.lang.Class_getAnnotation", (void *self, void *ann)) {
    const char *cls = jcl_str(self);
    const nabu_annotation_entry *e = nabu_get_annotation(cls, jcl_str(ann));
    return e != NULL ? jcl_ann_obj(e) : NULL;
}
BIND(void *, jcl_java_lang_Class_getClassLoader, "java.lang.Class_getClassLoader", (void *self)) { (void)self; return NULL; }
BIND(void *, jcl_java_lang_Class_getComponentType, "java.lang.Class_getComponentType", (void *self)) {
    const char *n = jcl_str(self);
    size_t len = strlen(n);
    /* JVM-vorm: "[Ljava/lang/String;", "[[I", "[C" — zoals de backend een
     * array-class-literal noemt. */
    if (n[0] == '[' && len > 1) {
        const char *elem = n + 1;
        if (elem[0] == '[') return jcl_class(elem);
        return jcl_class(jcl_desc_name(elem));
    }
    if (len > 2 && n[len - 1] == ']') {
        if (n[0] == 'L') return jcl_class(jcl_desc_name(n));
        return jcl_class(jcl_desc_name(n + 1));
    }
    return NULL;
}
/* Reflectieve instantiatie (Class/Constructor zijn VM-gebonden): de backend
 * emitteert per instantieerbare gecompileerde klasse met een no-arg-
 * constructor een entry; newInstance bouwt daarmee een echt object. Alleen
 * no-arg-constructors; andere geven NoSuchMethodException, zoals de JVM bij
 * een ontbrekende constructor. */
typedef struct nabu_ctor_entry {
    const char *name; void *type; void *vtable; void *itable; int64_t size; void (*init)(void *);
} nabu_ctor_entry;
extern const nabu_ctor_entry nabu_ctor_table[];
extern const int32_t nabu_ctor_count;
typedef struct { int kind; const nabu_ctor_entry *e; } s_ctor;

static const nabu_ctor_entry *jcl_ctor_entry(const char *iname) {
    for (int32_t i = 0; i < nabu_ctor_count; i++) {
        if (strcmp(nabu_ctor_table[i].name, iname) == 0) return &nabu_ctor_table[i];
    }
    return NULL;
}

static void *jcl_no_arg_ctor(void *cls, void *pts) {
    const char *n = jcl_str(cls);
    const nabu_ctor_entry *e = jcl_arr_len(pts) == 0 ? jcl_ctor_entry(n) : NULL;
    if (e == NULL) {
        nabu_throw(jcl_throw("java/lang/NoSuchMethodException", "java/lang/ReflectiveOperationException",
                jcl_concat(n, ".<init>")));
        return NULL;
    }
    s_ctor *st = (s_ctor *)jcl_alloc(sizeof(s_ctor));
    st->kind = K_CTOR;
    st->e = e;
    return jcl_new_obj("java/lang/reflect/Constructor", st);
}

static void *jcl_instantiate(const nabu_ctor_entry *e) {
    void *self = jcl_new_compiled(e->type, e->vtable, e->itable, e->size);
    e->init(self);
    return self;
}

BIND(void *, jcl_java_lang_Class_getConstructor, "java.lang.Class_getConstructor", (void *self, void *pts)) { return jcl_no_arg_ctor(self, pts); }
BIND(void *, jcl_java_lang_Class_getDeclaredConstructor, "java.lang.Class_getDeclaredConstructor", (void *self, void *pts)) { return jcl_no_arg_ctor(self, pts); }
BIND(void *, jcl_java_lang_Class_getDeclaredFields, "java.lang.Class_getDeclaredFields", (void *self)) {
    const nabu_reflection_info *ri = nabu_lookup_reflection(jcl_str(self));
    if (ri == NULL || ri->field_count <= 0) return jcl_arr_typed(0, "[Ljava/lang/reflect/Field;");
    void *arr = jcl_arr_typed(ri->field_count, "[Ljava/lang/reflect/Field;");
    for (int i = 0; i < ri->field_count; i++) jcl_arr_set(arr, i, jcl_field_obj(ri->type_name, &ri->fields[i]));
    return arr;
}
BIND(void *, jcl_java_lang_Class_getDeclaredMethod, "java.lang.Class_getDeclaredMethod", (void *self, void *nm, void *pts)) { (void)self; (void)nm; (void)pts; return NULL; }
BIND(void *, jcl_java_lang_Class_getDeclaredMethods, "java.lang.Class_getDeclaredMethods", (void *self)) { (void)self; return jcl_arr_typed(0, "[Ljava/lang/reflect/Method;"); }
/* Enum-reflectie via de door de backend geëmitteerde tabel (zie
 * LLVMModuleEmitter.emitEnumTable): per enum-klasse haar gecompileerde
 * static values(). Enum.valueOf(Class, String) loopt via
 * enumConstantDirectory; picocli gebruikt het voor TraceLevel en enum-opties. */
typedef struct nabu_enum_entry { const char *name; void *(*values)(void); } nabu_enum_entry;
extern const nabu_enum_entry nabu_enum_table[];
extern const int32_t nabu_enum_count;

static const nabu_enum_entry *jcl_enum_entry(void *cls) {
    const char *n = jcl_str(cls);
    for (int32_t i = 0; i < nabu_enum_count; i++) {
        if (strcmp(nabu_enum_table[i].name, n) == 0) return &nabu_enum_table[i];
    }
    return NULL;
}
BIND(void *, jcl_java_lang_Class_getEnumConstants, "java.lang.Class_getEnumConstants", (void *self)) {
    const nabu_enum_entry *e = jcl_enum_entry(self);
    return e != NULL ? e->values() : NULL;
}
BIND(void *, jcl_java_lang_Class_getEnumConstantsShared, "java.lang.Class_getEnumConstantsShared", (void *self)) {
    return jcl_java_lang_Class_getEnumConstants(self);
}
void *jcl_java_lang_Class_getSuperclass(void *self) __asm__("java.lang.Class_getSuperclass");

/* Class.getField: a public field of the class or of a superclass (JLS:
 * the class first). With reflection metadata generated for allPublicFields,
 * the superclasses have theirs too. Fields of a superclass without
 * metadata are not found. */
/* Class.getDeclaredField: a field of the reflection registry, else an instance field of a
 * compiled class (the JDK uses it with Unsafe.objectFieldOffset, for example in Random). */
BIND(void *, jcl_java_lang_Class_getDeclaredField, "java.lang.Class_getDeclaredField", (void *self, void *nm)) {
    const char *name = jcl_str(nm);
    const char *cls = jcl_str(self);
    const nabu_reflection_info *ri = nabu_lookup_reflection(cls);
    for (int i = 0; ri != NULL && i < ri->field_count; i++) {
        if (strcmp(ri->fields[i].name, name) == 0) return jcl_field_obj(ri->type_name, &ri->fields[i]);
    }
    char internal[512];
    size_t n = strlen(cls);
    if (n < sizeof internal) {
        for (size_t i = 0; i <= n; i++) internal[i] = cls[i] == '.' ? '/' : cls[i];
        const nabu_type_info *ti = nabu_compiled_type_for(internal);
        for (int i = 0; ti != NULL && i < ti->instance_field_count; i++) {
            if (strcmp(ti->instance_fields[i].name, name) == 0) {
                nabu_field_info *info = (nabu_field_info *)jcl_alloc(sizeof(nabu_field_info));
                info->name = jcl_dup(name);
                info->type_desc = "";
                info->offset = ti->instance_fields[i].offset;
                info->modifiers = 0x0002;
                return jcl_field_obj(cls, info);
            }
        }
    }
    /* A static field of a compiled class: its global is named "<class>_<field>" in the executable. */
    if (n < sizeof internal) {
        char symbol[640];
        snprintf(symbol, sizeof symbol, "%s_%s", cls, name);
        for (char *q = symbol; *q; q++) if (*q == '/') *q = '.';
        void *address = nabu_data_symbol(symbol);
        if (address != NULL) {
            nabu_field_info *info = (nabu_field_info *)jcl_alloc(sizeof(nabu_field_info));
            info->name = jcl_dup(name);
            info->type_desc = "";
            info->modifiers = 0x000A;
            info->static_address = address;
            return jcl_field_obj(cls, info);
        }
    }
    nabu_throw(jcl_throw("java/lang/NoSuchFieldException", "java/lang/ReflectiveOperationException", name));
    return NULL;
}
BIND(void *, jcl_java_lang_Class_getField, "java.lang.Class_getField", (void *self, void *nm)) {
    const char *name = jcl_str(nm);
    for (void *c = self; c != NULL; c = jcl_java_lang_Class_getSuperclass(c)) {
        const nabu_reflection_info *ri = nabu_lookup_reflection(jcl_str(c));
        if (ri == NULL) continue;
        for (int i = 0; i < ri->field_count; i++) {
            if ((ri->fields[i].modifiers & 0x0001) != 0 && strcmp(ri->fields[i].name, name) == 0) {
                return jcl_field_obj(ri->type_name, &ri->fields[i]);
            }
        }
    }
    nabu_throw(jcl_throw("java/lang/NoSuchFieldException", "java/lang/ReflectiveOperationException", name));
    return NULL;
}
/* Class.getFields: the public fields of the class and its superclasses. */
BIND(void *, jcl_java_lang_Class_getFields, "java.lang.Class_getFields", (void *self)) {
    int32_t count = 0;
    for (void *c = self; c != NULL; c = jcl_java_lang_Class_getSuperclass(c)) {
        const nabu_reflection_info *ri = nabu_lookup_reflection(jcl_str(c));
        if (ri == NULL) continue;
        for (int i = 0; i < ri->field_count; i++) if ((ri->fields[i].modifiers & 0x0001) != 0) count++;
    }
    void *arr = jcl_arr_ref(count);
    jcl_arr_set_type(arr, "[Ljava/lang/reflect/Field;");
    int32_t k = 0;
    for (void *c = self; c != NULL; c = jcl_java_lang_Class_getSuperclass(c)) {
        const nabu_reflection_info *ri = nabu_lookup_reflection(jcl_str(c));
        if (ri == NULL) continue;
        for (int i = 0; i < ri->field_count; i++) {
            if ((ri->fields[i].modifiers & 0x0001) != 0) jcl_arr_set(arr, k++, jcl_field_obj(ri->type_name, &ri->fields[i]));
        }
    }
    return arr;
}
BIND(void *, jcl_java_lang_Class_getMethod, "java.lang.Class_getMethod", (void *self, void *nm, void *pts)) { (void)self; (void)nm; (void)pts; return NULL; }
BIND(void *, jcl_java_lang_Class_getMethods, "java.lang.Class_getMethods", (void *self)) { (void)self; return jcl_arr_typed(0, "[Ljava/lang/reflect/Method;"); }
/* De super van de klasse die `self` NOEMT — niet die van het Class-object zelf
 * (dat is altijd java/lang/Class, met Object als super: getSuperclass gaf dan
 * Object.class terug, ook voor Object.class zelf, en picocli's
 * `while (cls != null) cls = cls.getSuperclass()` liep eindeloos). */
BIND(void *, jcl_java_lang_Class_getSuperclass, "java.lang.Class_getSuperclass", (void *self)) {
    static const char *const primitives[] = {
        "boolean", "byte", "char", "short", "int", "long", "float", "double", "void"
    };
    const char *n = jcl_str(self);
    for (size_t i = 0; i < sizeof primitives / sizeof primitives[0]; i++) {
        if (strcmp(n, primitives[i]) == 0) return NULL;
    }
    if (n[0] == '\0' || n[0] == '[' || strcmp(n, "java/lang/Object") == 0) {
        /* Object, primitieven en void hebben geen super; een array heeft
         * Object, maar picocli loopt er niet over. */
        return n[0] == '[' ? jcl_class("java/lang/Object") : NULL;
    }
    const nabu_type_info *ti = nabu_compiled_type_for(n);
    if (ti != NULL && ti->super_type != NULL && ti->super_type->name != NULL) {
        return jcl_class(ti->super_type->name);
    }
    /* Geen (gecompileerde) super bekend: een klasse erft dan van Object. */
    return jcl_class("java/lang/Object");
}
BIND(int, jcl_java_lang_Class_isArray, "java.lang.Class_isArray", (void *self)) {
    const char *n = jcl_str(self);
    size_t len = strlen(n);
    return (n[0] == '[' && len > 1) || (len > 1 && n[len - 1] == ']');
}
/* self.isAssignableFrom(o): gelijk, Object voor elke referentie, of via de
 * super-keten/interfaces van het gecompileerde type van o (zoals
 * nabu_instanceof). Sun's Bundles controleert zo of een reflectief geladen
 * klasse een ResourceBundle is. */
BIND(int, jcl_java_lang_Class_isAssignableFrom, "java.lang.Class_isAssignableFrom", (void *self, void *o)) {
    const char *target = jcl_str(self), *from = jcl_str(o);
    if (strcmp(target, from) == 0) return 1;
    const nabu_type_info *t = nabu_compiled_type_for(from);
    if (t != NULL && strcmp(target, "java/lang/Object") == 0) return 1;
    for (; t != NULL; t = t->super_type) {
        if (strcmp(t->name, target) == 0) return 1;
        for (int i = 0; i < t->interface_count; i++) {
            if (strcmp(t->interfaces[i], target) == 0) return 1;
        }
    }
    return 0;
}
BIND(int, jcl_java_lang_Class_isEnum, "java.lang.Class_isEnum", (void *self)) { return jcl_enum_entry(self) != NULL; }
BIND(int, jcl_java_lang_Class_isInterface, "java.lang.Class_isInterface", (void *self)) { (void)self; return 0; }
BIND(int, jcl_java_lang_Class_isPrimitive, "java.lang.Class_isPrimitive", (void *self)) {
    const char *n = jcl_str(self);
    return strcmp(n, "int") == 0 || strcmp(n, "long") == 0 || strcmp(n, "boolean") == 0
        || strcmp(n, "double") == 0 || strcmp(n, "float") == 0 || strcmp(n, "short") == 0
        || strcmp(n, "byte") == 0 || strcmp(n, "char") == 0 || strcmp(n, "void") == 0;
}
BIND(void *, jcl_java_lang_Class_newInstance, "java.lang.Class_newInstance", (void *self)) {
    const nabu_ctor_entry *e = jcl_ctor_entry(jcl_str(self));
    if (e == NULL) {
        nabu_throw(jcl_throw("java/lang/InstantiationException", "java/lang/ReflectiveOperationException", jcl_str(self)));
        return NULL;
    }
    return jcl_instantiate(e);
}
BIND(void *, jcl_java_lang_ClassLoader_getResource, "java.lang.ClassLoader_getResource", (void *self, void *nm)) { (void)self; (void)nm; return NULL; }
/* Een native programma heeft één (boot-)loader; null is de JDK-vorm daarvan,
 * net als Class.getClassLoader() hierboven. ServiceLoader.loadInstalled
 * (locale-data via LocaleDataMetaInfo) vraagt de platform-loader. */
BIND(void *, jcl_java_lang_ClassLoader_getPlatformClassLoader, "java.lang.ClassLoader_getPlatformClassLoader", (void)) { return NULL; }
BIND(void *, jcl_java_lang_ClassLoader_getSystemClassLoader, "java.lang.ClassLoader_getSystemClassLoader", (void)) { return NULL; }
/* Eén module voor alles (zoals de ene boot-loader): de ServiceLoader-
 * controle "tijdens VM-init" vergelijkt alleen de modules van aanroeper,
 * Object en service op identiteit. */
BIND(void *, jcl_java_lang_Class_getModule, "java.lang.Class_getModule", (void *self)) { (void)self; return NULL; }
/* VM-registratie van de unnamed module van de boot-loader (BootLoader.<clinit>):
 * er is geen VM die hem bijhoudt. */
BIND(void, jcl_jdk_internal_loader_BootLoader_setBootLoaderUnnamedModule0, "jdk.internal.loader.BootLoader_setBootLoaderUnnamedModule0", (void *module)) { (void)module; }
BIND(void *, jcl_java_lang_Annotation_annotationType, "java.lang.annotation.Annotation_annotationType", (void *self)) {
    if (self != NULL && nabu_jcl_is_object(self)) {
        nabu_object *h = (nabu_object *)self;
        nabu_type_info *ti = (nabu_type_info *)h->type;
        if (ti != NULL && ti->name != NULL) return jcl_class(ti->name);
    }
    return jcl_class("");
}

void *jcl_executable_annotation(void *self, void *ann);
int jcl_is_executable(void *self);
BIND(void *, jcl_java_lang_reflect_AccessibleObject_getAnnotation, "java.lang.reflect.AccessibleObject_getAnnotation", (void *self, void *ann)) {
    s_field *f = jcl_field_of(self);
    if (f != NULL) {
        const nabu_annotation_entry *e = nabu_get_field_annotation(f->cls, f->f->name, jcl_str(ann));
        return e != NULL ? jcl_ann_obj(e) : NULL;
    }
    if (jcl_is_executable(self)) return jcl_executable_annotation(self, ann);
    return jcl_java_lang_Class_getAnnotation(self, ann);
}
BIND(int, jcl_java_lang_reflect_AccessibleObject_isAccessible, "java.lang.reflect.AccessibleObject_isAccessible", (void *self)) { (void)self; return 1; }
BIND(int, jcl_java_lang_reflect_AccessibleObject_isAnnotationPresent, "java.lang.reflect.AccessibleObject_isAnnotationPresent", (void *self, void *ann)) {
    return jcl_java_lang_reflect_AccessibleObject_getAnnotation(self, ann) != NULL;
}
BIND(void, jcl_java_lang_reflect_AccessibleObject_setAccessible, "java.lang.reflect.AccessibleObject_setAccessible", (void *self, int v)) { (void)self; (void)v; }
/* AnnotatedElement-aanroepen (via de interface, dus ook op een Field) moeten
 * naar het soort ontvanger kijken: een Field heeft veld-annotaties, een Class
 * klasse-annotaties. Altijd de Class-variant nemen gaf voor elk veld "geen
 * annotaties" (picocli: "AutoHelpMixin ... is not a command"). */
BIND(void *, jcl_java_lang_reflect_AnnotatedElement_getAnnotation, "java.lang.reflect.AnnotatedElement_getAnnotation", (void *self, void *ann)) {
    if (jcl_field_of(self) != NULL || jcl_is_executable(self)) return jcl_java_lang_reflect_AccessibleObject_getAnnotation(self, ann);
    return jcl_java_lang_Class_getAnnotation(self, ann);
}
BIND(void *, jcl_java_lang_reflect_Array_get, "java.lang.reflect.Array_get", (void *a, int32_t i)) { return jcl_arr_elem(a, i); }
BIND(int32_t, jcl_java_lang_reflect_Array_getLength, "java.lang.reflect.Array_getLength", (void *a)) { return jcl_arr_len(a); }
/* Array.newInstance(component, n): an array with the component's element
 * size and class. Before, every array was one of references (8-byte
 * elements, so a copy of an int[] went wrong) without a class. */
BIND(void *, jcl_java_lang_reflect_Array_newInstance, "java.lang.reflect.Array_newInstance", (void *cls, int32_t n)) {
    if (cls == NULL) { nabu_throw(jcl_throw("java/lang/NullPointerException", "java/lang/RuntimeException", NULL)); return NULL; }
    if (n < 0) { nabu_throw(jcl_throw("java/lang/NegativeArraySizeException", "java/lang/RuntimeException", NULL)); return NULL; }
    const char *c = jcl_str(cls);
    static const struct { const char *name; const char *array; int32_t size; } prims[] = {
        {"boolean", "[Z", 1}, {"byte", "[B", 1}, {"char", "[C", 2}, {"short", "[S", 2},
        {"int", "[I", 4}, {"float", "[F", 4}, {"long", "[J", 8}, {"double", "[D", 8}};
    const char *arrayName = NULL;
    int32_t size = (int32_t)sizeof(void *);
    for (size_t i = 0; i < sizeof prims / sizeof prims[0]; i++) {
        if (strcmp(prims[i].name, c) == 0) { arrayName = prims[i].array; size = prims[i].size; }
    }
    if (arrayName == NULL) {
        arrayName = c[0] == '[' ? jcl_concat("[", c) : jcl_concat(jcl_concat("[L", c), ";");
    }
    void *p = jcl_alloc(8 + (size_t)n * (size_t)size);
    ((int32_t *)p)[0] = n;
    ((int32_t *)p)[1] = size;
    jcl_arr_set_type(p, arrayName);
    return p;
}

/* Object.getClass() of an array (the backend calls this for an array-typed
 * receiver; an array has no object header). */
void *nabu_array_class(void *a) {
    if (a == NULL) { nabu_throw_npe("getClass"); return NULL; }
    const char *name = jcl_arr_type_name(a);
    return jcl_class(name != NULL ? name : "[Ljava/lang/Object;");
}
BIND(void, jcl_java_lang_reflect_Array_set, "java.lang.reflect.Array_set", (void *a, int32_t i, void *v)) { jcl_arr_set(a, i, v); }
BIND(void *, jcl_java_lang_reflect_Constructor_newInstance, "java.lang.reflect.Constructor_newInstance", (void *self, void *args)) {
    s_ctor *st = (s_ctor *)jcl_state_kind(self, K_CTOR);
    if (st == NULL || jcl_arr_len(args) != 0) {
        nabu_throw(jcl_throw("java/lang/IllegalArgumentException", "java/lang/RuntimeException", "wrong number of arguments"));
        return NULL;
    }
    return jcl_instantiate(st->e);
}
/* Alleen no-arg-constructors staan in nabu_ctor_table, en native wordt
 * toegang niet afgedwongen: public. (ResourceBundleProviderHelper weigert een
 * niet-public constructor.) */
BIND(void, jcl_java_lang_reflect_Constructor_setAccessible, "java.lang.reflect.Constructor_setAccessible", (void *self, int v)) { (void)self; (void)v; }
static void * jcl_java_lang_reflect_Field_getName (void *self) {
    s_field *f = jcl_field_of(self);
    return (void *)(f != NULL ? f->f->name : "");
}
BIND(void *, jcl_java_lang_reflect_Field_getName_jstr, "java.lang.reflect.Field_getName", (void *self)) {
    void *r = (void *)jcl_java_lang_reflect_Field_getName(self);
    return r == NULL ? NULL : nabu_jstring_new(nabu_cstr(r));
}
BIND(void *, jcl_java_lang_reflect_Field_getDeclaringClass, "java.lang.reflect.Field_getDeclaringClass", (void *self)) {
    s_field *f = jcl_field_of(self);
    return jcl_class(f != NULL ? f->cls : "");
}
void *jcl_field_generic_type(void *self);
BIND(void *, jcl_java_lang_reflect_Field_getGenericType, "java.lang.reflect.Field_getGenericType", (void *self)) {
    return jcl_field_generic_type(self);
}
BIND(void *, jcl_java_lang_reflect_Field_getType, "java.lang.reflect.Field_getType", (void *self)) {
    s_field *f = jcl_field_of(self);
    return jcl_class(f != NULL ? jcl_desc_name(f->f->type_desc) : "");
}
BIND(int32_t, jcl_java_lang_reflect_Field_getModifiers, "java.lang.reflect.Field_getModifiers", (void *self)) {
    s_field *f = jcl_field_of(self);
    return f != NULL ? f->f->modifiers : 0;
}
static void * jcl_java_lang_reflect_Field_toGenericString (void *self) {
    s_field *f = jcl_field_of(self);
    if (f == NULL) return (void *)"";
    const char *tn = jcl_desc_name(f->f->type_desc);
    size_t n = strlen(tn) + strlen(f->cls) + strlen(f->f->name) + 4;
    char *o = (char *)jcl_alloc(n);
    snprintf(o, n, "%s %s.%s", tn, f->cls, f->f->name);
    return (void *)o;
}
BIND(void *, jcl_java_lang_reflect_Field_toGenericString_jstr, "java.lang.reflect.Field_toGenericString", (void *self)) {
    void *r = (void *)jcl_java_lang_reflect_Field_toGenericString(self);
    return r == NULL ? NULL : nabu_jstring_new(nabu_cstr(r));
}
/* Field access. A field's value lives at the object plus its offset, or for
 * a static field in its global (static_address, after the class is
 * initialized). It is read and written with the width of its type: a
 * boolean or byte is one byte, a char or short two. The JDK's widening
 * rules apply to the typed accessors (getLong on an int field, setInt on a
 * double field); anything else is an IllegalArgumentException. */
static char *jcl_field_addr(s_field *f, void *obj) {
    if (f->f->static_address != NULL) {
        if (f->f->class_init != NULL) f->f->class_init();
        return (char *)f->f->static_address;
    }
    if (obj == NULL) {
        nabu_throw(jcl_throw("java/lang/NullPointerException", "java/lang/RuntimeException", f->f->name));
        return NULL;
    }
    return (char *)obj + f->f->offset;
}
static void jcl_field_type_mismatch(s_field *f) {
    nabu_throw(jcl_throw("java/lang/IllegalArgumentException", "java/lang/RuntimeException",
            jcl_concat("Can not convert the value of field ", f->f->name)));
}
/* The value of a primitive field as an integer (char unsigned, boolean 0/1). */
static int64_t jcl_field_read_integral(char t, const char *a) {
    switch (t) {
        case 'Z': return *(const uint8_t *)a != 0;
        case 'B': return *(const int8_t *)a;
        case 'S': return *(const int16_t *)a;
        case 'C': return *(const uint16_t *)a;
        case 'I': return *(const int32_t *)a;
        case 'J': return *(const int64_t *)a;
        case 'F': return (int64_t)*(const float *)a;
        case 'D': return (int64_t)*(const double *)a;
        default: return 0;
    }
}
static double jcl_field_read_number(char t, const char *a) {
    switch (t) {
        case 'F': return *(const float *)a;
        case 'D': return *(const double *)a;
        default: return (double)jcl_field_read_integral(t, a);
    }
}
static void jcl_field_write(char t, char *a, int64_t i, double d, int floating) {
    switch (t) {
        case 'Z': *(uint8_t *)a = i != 0; return;
        case 'B': *(int8_t *)a = (int8_t)i; return;
        case 'S': *(int16_t *)a = (int16_t)i; return;
        case 'C': *(uint16_t *)a = (uint16_t)i; return;
        case 'I': *(int32_t *)a = (int32_t)i; return;
        case 'J': *(int64_t *)a = i; return;
        case 'F': *(float *)a = (float)(floating ? d : (double)i); return;
        case 'D': *(double *)a = floating ? d : (double)i; return;
        default: return;
    }
}
BIND(void *, jcl_java_lang_reflect_Field_get, "java.lang.reflect.Field_get", (void *self, void *obj)) {
    s_field *f = jcl_field_of(self);
    if (f == NULL) return NULL;
    char *a = jcl_field_addr(f, obj);
    if (a == NULL) return NULL;
    const char t = f->f->type_desc[0];
    switch (t) {
        case 'Z': return box_bool((int)jcl_field_read_integral(t, a));
        case 'B': return jcl_box(B_I8, jcl_field_read_integral(t, a), 0);
        case 'S': return box_i16((int32_t)jcl_field_read_integral(t, a));
        case 'C': return jcl_box(B_CHAR, jcl_field_read_integral(t, a), 0);
        case 'I': return box_i32((int32_t)jcl_field_read_integral(t, a));
        case 'J': return box_i64(jcl_field_read_integral(t, a));
        case 'F': return jcl_box(B_F32, 0, jcl_field_read_number(t, a));
        case 'D': return box_f64(jcl_field_read_number(t, a));
        default: return *(void **)a;
    }
}
/* The getter `sym` for a field whose type letter is in `allowed`. */
#define JCL_FIELD_GET(ret, sym, cname, allowed, number) \
BIND(ret, cname, sym, (void *self, void *obj)) { \
    s_field *f = jcl_field_of(self); \
    if (f == NULL) return 0; \
    const char t = f->f->type_desc[0]; \
    if (strchr(allowed, t) == NULL || t == 0) { jcl_field_type_mismatch(f); return 0; } \
    char *a = jcl_field_addr(f, obj); \
    if (a == NULL) return 0; \
    return number ? (ret)jcl_field_read_number(t, a) : (ret)jcl_field_read_integral(t, a); \
}
JCL_FIELD_GET(int32_t, "java.lang.reflect.Field_getInt", jcl_java_lang_reflect_Field_getInt, "BSCI", 0)
JCL_FIELD_GET(int64_t, "java.lang.reflect.Field_getLong", jcl_java_lang_reflect_Field_getLong, "BSCIJ", 0)
JCL_FIELD_GET(int16_t, "java.lang.reflect.Field_getShort", jcl_java_lang_reflect_Field_getShort, "BS", 0)
JCL_FIELD_GET(int8_t, "java.lang.reflect.Field_getByte", jcl_java_lang_reflect_Field_getByte, "B", 0)
JCL_FIELD_GET(uint16_t, "java.lang.reflect.Field_getChar", jcl_java_lang_reflect_Field_getChar, "C", 0)
JCL_FIELD_GET(int, "java.lang.reflect.Field_getBoolean", jcl_java_lang_reflect_Field_getBoolean, "Z", 0)
JCL_FIELD_GET(float, "java.lang.reflect.Field_getFloat", jcl_java_lang_reflect_Field_getFloat, "BSCIJF", 1)
JCL_FIELD_GET(double, "java.lang.reflect.Field_getDouble", jcl_java_lang_reflect_Field_getDouble, "BSCIJFD", 1)
/* Stores a value of source kind `from` (a descriptor letter) into a field it widens to. */
static void jcl_field_store(void *self, void *obj, char from, int64_t i, double d) {
    s_field *f = jcl_field_of(self);
    if (f == NULL) return;
    static const char *const widens_to[] = { "BSIJFD", "SIJFD", "CIJFD", "IJFD", "JFD", "FD", "D", "Z" };
    const char *kinds = "BSCIJFDZ";
    const char *k = strchr(kinds, from);
    const char t = f->f->type_desc[0];
    if (k == NULL || t == 0 || strchr(widens_to[k - kinds], t) == NULL) {
        jcl_field_type_mismatch(f);
        return;
    }
    char *a = jcl_field_addr(f, obj);
    if (a == NULL) return;
    jcl_field_write(t, a, i, d, from == 'F' || from == 'D');
}
BIND(void, jcl_java_lang_reflect_Field_setInt, "java.lang.reflect.Field_setInt", (void *self, void *obj, int32_t v)) { jcl_field_store(self, obj, 'I', v, 0); }
BIND(void, jcl_java_lang_reflect_Field_setLong, "java.lang.reflect.Field_setLong", (void *self, void *obj, int64_t v)) { jcl_field_store(self, obj, 'J', v, 0); }
BIND(void, jcl_java_lang_reflect_Field_setShort, "java.lang.reflect.Field_setShort", (void *self, void *obj, int16_t v)) { jcl_field_store(self, obj, 'S', v, 0); }
BIND(void, jcl_java_lang_reflect_Field_setByte, "java.lang.reflect.Field_setByte", (void *self, void *obj, int8_t v)) { jcl_field_store(self, obj, 'B', v, 0); }
BIND(void, jcl_java_lang_reflect_Field_setChar, "java.lang.reflect.Field_setChar", (void *self, void *obj, uint16_t v)) { jcl_field_store(self, obj, 'C', v, 0); }
BIND(void, jcl_java_lang_reflect_Field_setBoolean, "java.lang.reflect.Field_setBoolean", (void *self, void *obj, int v)) { jcl_field_store(self, obj, 'Z', v != 0, 0); }
BIND(void, jcl_java_lang_reflect_Field_setDouble, "java.lang.reflect.Field_setDouble", (void *self, void *obj, double v)) { jcl_field_store(self, obj, 'D', (int64_t)v, v); }
BIND(void, jcl_java_lang_reflect_Field_setFloat, "java.lang.reflect.Field_setFloat", (void *self, void *obj, float v)) { jcl_field_store(self, obj, 'F', (int64_t)v, v); }
/* Field.set: a primitive field takes the value of its wrapper. */
BIND(void, jcl_java_lang_reflect_Field_set, "java.lang.reflect.Field_set", (void *self, void *obj, void *val)) {
    s_field *f = jcl_field_of(self);
    if (f == NULL) return;
    const char t = f->f->type_desc[0];
    if (t != 'L' && t != '[') {
        if (val == NULL) {
            jcl_field_type_mismatch(f);
            return;
        }
        char *a = jcl_field_addr(f, obj);
        if (a == NULL) return;
        jcl_field_write(t, a, t == 'Z' ? box_as_i64(val) != 0 : box_as_i64(val), box_as_f64(val), t == 'F' || t == 'D');
        return;
    }
    char *a = jcl_field_addr(f, obj);
    if (a == NULL) return;
    *(void **)a = val;
}
/* java.lang.reflect.Method and Constructor: a declaring class, a name
 * ("<init>" for a constructor), a JVM descriptor, the access flags, the
 * declared exceptions and the generic signature (K_METHOD). They come from
 * the generated ReflectionData (members of the reflection configuration)
 * and the proxy classes, through ProxySupport.method, which caches them
 * (one object per method, so == and equals agree). Method.invoke and
 * Constructor.newInstance on them are redirected to ProxySupport and
 * ReflectSupport, which dispatch in bytecode.
 *
 * Annotations come from the reflection metadata of the declaring class:
 * the owner of a method's annotation is "name(descriptor)", that of its
 * parameter i "name(descriptor)#i". */
typedef struct {
    int kind; char *cls; char *name; char *desc; int32_t modifiers; char *exceptions; char *signature;
} s_method;

/* Set by the compiler next to the access flags: declared in an interface
 * (Method.isDefault), a constructor of an abstract class. */
#define JCL_INTERFACE_OWNER 0x40000000
#define JCL_ABSTRACT_OWNER 0x20000000

static s_method *jcl_method_of(void *self) { return (s_method *)jcl_state_kind(self, K_METHOD); }

int jcl_is_executable(void *self) { return jcl_method_of(self) != NULL; }

static int jcl_is_constructor(const s_method *m) { return strcmp(m->name, "<init>") == 0; }

/* ProxySupport.method0(Class declaring, String name, String descriptor, int modifiers,
 *                      String exceptions, String signature): a Method, or a Constructor for <init>. */
BIND(void *, jcl_proxy_method0, "io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime.ProxySupport_method0",
     (void *cls, void *name, void *desc, int32_t modifiers, void *exceptions, void *signature)) {
    s_method *st = (s_method *)jcl_alloc(sizeof(s_method));
    st->kind = K_METHOD;
    st->cls = jcl_dup(jcl_str(cls));
    st->name = jcl_dup(jcl_str(name));
    st->desc = jcl_dup(jcl_str(desc));
    st->modifiers = modifiers;
    st->exceptions = exceptions != NULL ? jcl_dup(jcl_str(exceptions)) : NULL;
    st->signature = signature != NULL ? jcl_dup(jcl_str(signature)) : NULL;
    return jcl_new_obj(jcl_is_constructor(st) ? "java/lang/reflect/Constructor" : "java/lang/reflect/Method", st);
}

/* ProxySupport.methodKey(Object): "owner#name#descriptor", or null for an
 * object without K_METHOD state (a no-argument Constructor of the C runtime). */
BIND(void *, jcl_proxy_methodKey, "io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime.ProxySupport_methodKey",
     (void *method)) {
    s_method *m = jcl_method_of(method);
    if (m == NULL) return NULL;
    return nabu_jstring_new(jcl_concat(jcl_concat(jcl_concat(jcl_concat(m->cls, "#"), m->name), "#"), m->desc));
}

/* Length of the field descriptor at d ("I", "[J", "Ljava/lang/String;"). */
static size_t jcl_desc_len(const char *d) {
    size_t n = 0;
    while (d[n] == '[') n++;
    if (d[n] == 'L') {
        while (d[n] != ';' && d[n] != 0) n++;
    }
    return d[n] == 0 ? n : n + 1;
}

/* The Class of the field descriptor of length n at d. */
static void *jcl_desc_class(const char *d, size_t n) {
    char buf[512];
    if (n >= sizeof buf) n = sizeof buf - 1;
    memcpy(buf, d, n);
    buf[n] = 0;
    return jcl_class(jcl_desc_name(buf));
}

/* The source form of a field descriptor ("java.lang.String[]", "int"). */
static char *jcl_desc_type_name(const char *d, size_t n) {
    size_t dims = 0;
    while (dims < n && d[dims] == '[') dims++;
    char base[512];
    size_t len = n - dims;
    if (len >= sizeof base) len = sizeof base - 1;
    memcpy(base, d + dims, len);
    base[len] = 0;
    char *name = jcl_dup(jcl_desc_name(base));
    for (char *c = name; *c; c++) if (*c == '/') *c = '.';
    for (size_t i = 0; i < dims; i++) name = jcl_concat(name, "[]");
    return name;
}

static int32_t jcl_exec_param_count(const s_method *m) {
    int32_t count = 0;
    for (const char *d = m->desc + 1; *d != ')' && *d != 0; d += jcl_desc_len(d)) count++;
    return count;
}

/* "name(descriptor)": the owner of the method's annotations in the metadata. */
static char *jcl_exec_annotation_owner(const s_method *m) {
    return jcl_concat(m->name, m->desc);
}

/* --- Generic types ---------------------------------------------------
 * A generic signature (JVMS 4.7.9.1) parsed into the reflection types:
 * Class for a plain type, and objects of the runtime (K_GTYPE) for a
 * ParameterizedType, TypeVariable, WildcardType or GenericArrayType. A type
 * variable's bound comes from the formal type parameters in scope: the
 * method's own and its class's (the class signature in the reflection
 * metadata). */
enum { G_PARAM = 1, G_VAR, G_WILDCARD, G_ARRAY };
typedef struct {
    int kind; int g; char *text;
    void *raw;                  /* G_PARAM: the Class */
    void **args; int32_t argc;  /* G_PARAM: type arguments */
    void *bound;                /* G_VAR: its bound; G_WILDCARD: upper bound; G_ARRAY: component */
    void *lower;                /* G_WILDCARD: lower bound, or NULL */
} s_gtype;

/* Type variable names and their bounds, from formal type parameters (the
 * class's, then the method's: a later entry shadows an earlier one). */
typedef struct { const char *name[32]; void *bound[32]; int count; } jcl_type_scope;

typedef struct { void *type; int generic; char *text; char *erased; } jcl_parsed;

static jcl_parsed jcl_parse_type(const char **p, jcl_type_scope *scope);

static void *jcl_gtype_new(s_gtype *g, const char *iface) {
    g->kind = K_GTYPE;
    return jcl_new_obj(iface, g);
}

static char *jcl_type_text(void *type) {
    s_gtype *g = (s_gtype *)jcl_state_kind(type, K_GTYPE);
    if (g != NULL) return g->text;
    return jcl_binary_name(jcl_str(type));
}

static jcl_parsed jcl_parse_type(const char **p, jcl_type_scope *scope) {
    jcl_parsed r = {NULL, 0, NULL, NULL};
    const char c = **p;
    if (c == '[') {
        (*p)++;
        jcl_parsed component = jcl_parse_type(p, scope);
        r.erased = jcl_concat("[", component.erased);
        r.text = jcl_concat(component.text, "[]");
        if (!component.generic) {
            r.type = jcl_class(r.erased);
        } else {
            s_gtype *g = (s_gtype *)jcl_alloc(sizeof(s_gtype));
            g->g = G_ARRAY;
            g->text = r.text;
            g->bound = component.type;
            r.type = jcl_gtype_new(g, "java/lang/reflect/GenericArrayType");
            r.generic = 1;
        }
        return r;
    }
    if (c == 'T') {
        const char *start = ++(*p);
        while (**p != ';' && **p != 0) (*p)++;
        char *name = (char *)jcl_alloc((size_t)(*p - start) + 1);
        memcpy(name, start, (size_t)(*p - start));
        name[*p - start] = 0;
        if (**p == ';') (*p)++;
        s_gtype *g = (s_gtype *)jcl_alloc(sizeof(s_gtype));
        g->g = G_VAR;
        g->text = name;
        g->bound = jcl_class("java/lang/Object");
        for (int i = 0; scope != NULL && i < scope->count; i++) {
            if (strcmp(scope->name[i], name) == 0 && scope->bound[i] != NULL) g->bound = scope->bound[i];
        }
        r.type = jcl_gtype_new(g, "java/lang/reflect/TypeVariable");
        r.generic = 1;
        r.text = name;
        r.erased = "Ljava/lang/Object;";
        return r;
    }
    if (c == '*' || c == '+' || c == '-') {
        (*p)++;
        s_gtype *g = (s_gtype *)jcl_alloc(sizeof(s_gtype));
        g->g = G_WILDCARD;
        if (c == '*') {
            g->bound = jcl_class("java/lang/Object");
            g->text = "?";
        } else {
            jcl_parsed bound = jcl_parse_type(p, scope);
            if (c == '+') {
                g->bound = bound.type;
                g->text = jcl_concat("? extends ", bound.text);
            } else {
                g->bound = jcl_class("java/lang/Object");
                g->lower = bound.type;
                g->text = jcl_concat("? super ", bound.text);
            }
        }
        r.type = jcl_gtype_new(g, "java/lang/reflect/WildcardType");
        r.generic = 1;
        r.text = g->text;
        r.erased = "Ljava/lang/Object;";
        return r;
    }
    if (c == 'L') {
        /* Lpkg/Outer<args>.Inner<args>; : the raw class is pkg/Outer$Inner, the
         * type arguments those of the last part. */
        char rawname[512];
        size_t n = 0;
        void *args[32];
        int32_t argc = 0;
        (*p)++;
        while (**p != ';' && **p != 0) {
            if (**p == '<') {
                (*p)++;
                argc = 0;
                char *list = jcl_dup("");
                while (**p != '>' && **p != 0) {
                    jcl_parsed arg = jcl_parse_type(p, scope);
                    if (argc < 32) args[argc++] = arg.type;
                    list = jcl_concat(list, argc > 1 ? jcl_concat(", ", arg.text) : arg.text);
                }
                if (**p == '>') (*p)++;
                r.text = list;
            } else if (**p == '.') {
                if (n < sizeof rawname - 1) rawname[n++] = '$';
                (*p)++;
                argc = 0;
            } else {
                if (n < sizeof rawname - 1) rawname[n++] = **p;
                (*p)++;
            }
        }
        if (**p == ';') (*p)++;
        rawname[n] = 0;
        void *raw = jcl_class(rawname);
        r.erased = jcl_concat(jcl_concat("L", rawname), ";");
        char *rawText = jcl_binary_name(rawname);
        if (argc == 0) {
            r.type = raw;
            r.text = rawText;
            return r;
        }
        s_gtype *g = (s_gtype *)jcl_alloc(sizeof(s_gtype));
        g->g = G_PARAM;
        g->raw = raw;
        g->argc = argc;
        g->args = (void **)jcl_alloc(sizeof(void *) * (size_t)argc);
        memcpy(g->args, args, sizeof(void *) * (size_t)argc);
        g->text = jcl_concat(jcl_concat(jcl_concat(rawText, "<"), r.text), ">");
        r.type = jcl_gtype_new(g, "java/lang/reflect/ParameterizedType");
        r.generic = 1;
        r.text = g->text;
        return r;
    }
    /* A primitive type (or void). */
    char d[2] = {c, 0};
    if (c != 0) (*p)++;
    r.type = jcl_class(jcl_desc_name(d));
    r.text = (char *)jcl_desc_name(d);
    r.erased = jcl_dup(d);
    return r;
}

/* Skips (and records) the formal type parameters "<T:bound;U::bound;>". */
static const char *jcl_parse_formals(const char *p, jcl_type_scope *scope, void **variables, int32_t *count) {
    *count = 0;
    if (*p != '<') return p;
    p++;
    while (*p != '>' && *p != 0) {
        const char *start = p;
        while (*p != ':' && *p != 0) p++;
        char *name = (char *)jcl_alloc((size_t)(p - start) + 1);
        memcpy(name, start, (size_t)(p - start));
        name[p - start] = 0;
        void *bound = NULL;
        while (*p == ':') {
            p++;
            if (*p != ':' && *p != '>' && *p != 0) {
                jcl_parsed b = jcl_parse_type(&p, scope);
                if (bound == NULL) bound = b.type;
            }
        }
        if (scope->count < 32) {
            scope->name[scope->count] = name;
            scope->bound[scope->count] = bound != NULL ? bound : jcl_class("java/lang/Object");
            scope->count++;
        }
        if (variables != NULL && *count < 16) {
            s_gtype *g = (s_gtype *)jcl_alloc(sizeof(s_gtype));
            g->g = G_VAR;
            g->text = name;
            g->bound = bound != NULL ? bound : jcl_class("java/lang/Object");
            variables[(*count)++] = jcl_gtype_new(g, "java/lang/reflect/TypeVariable");
        }
    }
    return *p == '>' ? p + 1 : p;
}

/* The type variables of a class (from its generic signature in the
 * reflection metadata) into `scope`, and as objects into `variables`. */
static void jcl_class_scope(const char *cls, jcl_type_scope *scope, void **variables, int32_t *count) {
    const nabu_reflection_info *ri = nabu_lookup_reflection(cls);
    int32_t ignored;
    if (count == NULL) count = &ignored;
    *count = 0;
    if (ri != NULL && ri->signature != NULL && ri->signature[0] == '<') {
        jcl_parse_formals(ri->signature, scope, variables, count);
    }
}

/* The generic parameter types (want_return 0) or return type (1) of a method. */
static void *jcl_exec_generic(s_method *m, int want_return) {
    jcl_type_scope scope = {{0}, {0}, 0};
    void *variables[16];
    int32_t nvars;
    /* The class's type variables first: a method's own ones shadow them. */
    jcl_class_scope(m->cls, &scope, variables, &nvars);
    const char *p = jcl_parse_formals(m->signature, &scope, variables, &nvars);
    if (*p == '(') p++;
    void *params[64];
    int32_t count = 0;
    while (*p != ')' && *p != 0) {
        jcl_parsed t = jcl_parse_type(&p, &scope);
        if (count < 64) params[count++] = t.type;
    }
    if (*p == ')') p++;
    if (want_return) return jcl_parse_type(&p, &scope).type;
    void *arr = jcl_arr_ref(count);
    jcl_arr_set_type(arr, "[Ljava/lang/reflect/Type;");
    for (int32_t i = 0; i < count; i++) jcl_arr_set(arr, i, params[i]);
    return arr;
}

void *jcl_field_generic_type(void *self) {
    s_field *f = jcl_field_of(self);
    if (f == NULL) return jcl_class("");
    if (f->f->signature == NULL) return jcl_class(jcl_desc_name(f->f->type_desc));
    jcl_type_scope scope = {{0}, {0}, 0};
    jcl_class_scope(f->cls, &scope, NULL, NULL);
    const char *p = f->f->signature;
    return jcl_parse_type(&p, &scope).type;
}

/* Class.getTypeParameters: the type variables of the class's generic
 * signature (a class in the reflection configuration), else none. */
BIND(void *, jcl_java_lang_Class_getTypeParameters, "java.lang.Class_getTypeParameters", (void *self)) {
    jcl_type_scope scope = {{0}, {0}, 0};
    void *variables[16];
    int32_t count = 0;
    jcl_class_scope(jcl_str(self), &scope, variables, &count);
    void *arr = jcl_arr_ref(count);
    jcl_arr_set_type(arr, "[Ljava/lang/reflect/TypeVariable;");
    for (int32_t i = 0; i < count; i++) jcl_arr_set(arr, i, variables[i]);
    return arr;
}

static void *jcl_gtype_name_jstr(void *self) {
    return nabu_jstring_new(jcl_type_text(self));
}

static s_gtype *jcl_gtype_of(void *self, int g) {
    s_gtype *t = (s_gtype *)jcl_state_kind(self, K_GTYPE);
    return t != NULL && t->g == g ? t : NULL;
}

static void *jcl_type_array(void **types, int32_t n) {
    void *arr = jcl_arr_ref(n);
    jcl_arr_set_type(arr, "[Ljava/lang/reflect/Type;");
    for (int32_t i = 0; i < n; i++) jcl_arr_set(arr, i, types[i]);
    return arr;
}

BIND(void *, jcl_java_lang_reflect_ParameterizedType_getActualTypeArguments, "java.lang.reflect.ParameterizedType_getActualTypeArguments", (void *self)) {
    s_gtype *g = jcl_gtype_of(self, G_PARAM);
    return g != NULL ? jcl_type_array(g->args, g->argc) : jcl_arr_typed(0, "[Ljava/lang/reflect/Type;");
}
BIND(void *, jcl_java_lang_reflect_ParameterizedType_getRawType, "java.lang.reflect.ParameterizedType_getRawType", (void *self)) {
    s_gtype *g = jcl_gtype_of(self, G_PARAM);
    return g != NULL ? g->raw : jcl_class("");
}
BIND(void *, jcl_java_lang_reflect_ParameterizedType_getOwnerType, "java.lang.reflect.ParameterizedType_getOwnerType", (void *self)) { (void)self; return NULL; }
BIND(void *, jcl_java_lang_reflect_WildcardType_getUpperBounds, "java.lang.reflect.WildcardType_getUpperBounds", (void *self)) {
    s_gtype *g = jcl_gtype_of(self, G_WILDCARD);
    return g != NULL ? jcl_type_array(&g->bound, 1) : jcl_arr_typed(0, "[Ljava/lang/reflect/Type;");
}
BIND(void *, jcl_java_lang_reflect_WildcardType_getLowerBounds, "java.lang.reflect.WildcardType_getLowerBounds", (void *self)) {
    s_gtype *g = jcl_gtype_of(self, G_WILDCARD);
    return g != NULL && g->lower != NULL ? jcl_type_array(&g->lower, 1) : jcl_type_array(NULL, 0);
}
BIND(void *, jcl_java_lang_reflect_GenericArrayType_getGenericComponentType, "java.lang.reflect.GenericArrayType_getGenericComponentType", (void *self)) {
    s_gtype *g = jcl_gtype_of(self, G_ARRAY);
    return g != NULL ? g->bound : jcl_class("");
}
BIND(void *, jcl_java_lang_reflect_TypeVariable_getName, "java.lang.reflect.TypeVariable_getName", (void *self)) {
    s_gtype *g = jcl_gtype_of(self, G_VAR);
    return nabu_jstring_new(g != NULL ? g->text : "");
}
BIND(void *, jcl_java_lang_reflect_TypeVariable_getBounds, "java.lang.reflect.TypeVariable_getBounds", (void *self)) {
    s_gtype *g = jcl_gtype_of(self, G_VAR);
    return g != NULL ? jcl_type_array(&g->bound, 1) : jcl_arr_typed(0, "[Ljava/lang/reflect/Type;");
}
BIND(void *, jcl_java_lang_reflect_TypeVariable_getGenericDeclaration, "java.lang.reflect.TypeVariable_getGenericDeclaration", (void *self)) { (void)self; return NULL; }
/* Type.getTypeName is the interface's default (toString), which reaches
 * jcl_library_tostring for these objects; binding it here would replace the
 * default for every compiled class too. */

/* --- Members of Method, Constructor and Executable -------------------- */

static void * jcl_java_lang_reflect_Method_getName (void *self) {
    s_method *m = jcl_method_of(self);
    if (m == NULL) return (void *)"";
    return (void *)(jcl_is_constructor(m) ? jcl_binary_name(m->cls) : m->name);
}
BIND(void *, jcl_java_lang_reflect_Method_getName_jstr, "java.lang.reflect.Method_getName", (void *self)) {
    void *r = (void *)jcl_java_lang_reflect_Method_getName(self);
    return r == NULL ? NULL : nabu_jstring_new(nabu_cstr(r));
}
BIND(void *, jcl_java_lang_reflect_Method_getDeclaringClass, "java.lang.reflect.Method_getDeclaringClass", (void *self)) {
    s_method *m = jcl_method_of(self);
    return jcl_class(m != NULL ? m->cls : "");
}
BIND(void *, jcl_java_lang_reflect_Method_getReturnType, "java.lang.reflect.Method_getReturnType", (void *self)) {
    s_method *m = jcl_method_of(self);
    if (m == NULL) return jcl_class("void");
    const char *r = strchr(m->desc, ')');
    return r == NULL ? jcl_class("void") : jcl_desc_class(r + 1, strlen(r + 1));
}
BIND(void *, jcl_java_lang_reflect_Method_getGenericReturnType, "java.lang.reflect.Method_getGenericReturnType", (void *self)) {
    s_method *m = jcl_method_of(self);
    if (m != NULL && m->signature != NULL) return jcl_exec_generic(m, 1);
    return jcl_java_lang_reflect_Method_getReturnType(self);
}
BIND(void *, jcl_java_lang_reflect_Method_getParameterTypes, "java.lang.reflect.Method_getParameterTypes", (void *self)) {
    s_method *m = jcl_method_of(self);
    if (m == NULL || m->desc[0] != '(') {
        void *empty = jcl_arr_ref(0);
        jcl_arr_set_type(empty, "[Ljava/lang/Class;");
        return empty;
    }
    void *arr = jcl_arr_ref(jcl_exec_param_count(m));
    jcl_arr_set_type(arr, "[Ljava/lang/Class;");
    int32_t i = 0;
    for (const char *d = m->desc + 1; *d != ')' && *d != 0; d += jcl_desc_len(d)) {
        jcl_arr_set(arr, i++, jcl_desc_class(d, jcl_desc_len(d)));
    }
    return arr;
}
BIND(int32_t, jcl_java_lang_reflect_Method_getParameterCount, "java.lang.reflect.Method_getParameterCount", (void *self)) {
    s_method *m = jcl_method_of(self);
    return m != NULL && m->desc[0] == '(' ? jcl_exec_param_count(m) : 0;
}
BIND(void *, jcl_java_lang_reflect_Method_getGenericParameterTypes, "java.lang.reflect.Method_getGenericParameterTypes", (void *self)) {
    s_method *m = jcl_method_of(self);
    if (m != NULL && m->signature != NULL) return jcl_exec_generic(m, 0);
    return jcl_java_lang_reflect_Method_getParameterTypes(self);
}
BIND(void *, jcl_java_lang_reflect_Method_getTypeParameters, "java.lang.reflect.Method_getTypeParameters", (void *self)) {
    s_method *m = jcl_method_of(self);
    void *variables[16];
    int32_t count = 0;
    if (m != NULL && m->signature != NULL) {
        jcl_type_scope scope = {{0}, {0}, 0};
        void *classVariables[16];
        int32_t classCount;
        jcl_class_scope(m->cls, &scope, classVariables, &classCount);
        jcl_parse_formals(m->signature, &scope, variables, &count);
    }
    void *arr = jcl_arr_ref(count);
    jcl_arr_set_type(arr, "[Ljava/lang/reflect/TypeVariable;");
    for (int32_t i = 0; i < count; i++) jcl_arr_set(arr, i, variables[i]);
    return arr;
}
BIND(int32_t, jcl_java_lang_reflect_Method_getModifiers, "java.lang.reflect.Method_getModifiers", (void *self)) {
    s_method *m = jcl_method_of(self);
    /* A no-argument constructor from the C runtime's table: public (access is not enforced). */
    return m != NULL ? m->modifiers & ~(JCL_INTERFACE_OWNER | JCL_ABSTRACT_OWNER) : 1;
}
BIND(int, jcl_java_lang_reflect_Method_isVarArgs, "java.lang.reflect.Method_isVarArgs", (void *self)) {
    s_method *m = jcl_method_of(self);
    return m != NULL && (m->modifiers & 0x0080) != 0;
}
BIND(int, jcl_java_lang_reflect_Method_isBridge, "java.lang.reflect.Method_isBridge", (void *self)) {
    s_method *m = jcl_method_of(self);
    return m != NULL && (m->modifiers & 0x0040) != 0;
}
BIND(int, jcl_java_lang_reflect_Method_isSynthetic, "java.lang.reflect.Method_isSynthetic", (void *self)) {
    s_method *m = jcl_method_of(self);
    return m != NULL && (m->modifiers & 0x1000) != 0;
}
/* A public, non-abstract instance method of an interface (JLS 9.4). */
BIND(int, jcl_java_lang_reflect_Method_isDefault, "java.lang.reflect.Method_isDefault", (void *self)) {
    s_method *m = jcl_method_of(self);
    return m != NULL && (m->modifiers & JCL_INTERFACE_OWNER) != 0
            && (m->modifiers & (0x0001 | 0x0008 | 0x0400)) == 0x0001;
}
/* The declared exceptions (internal names, comma-separated in the record). */
BIND(void *, jcl_java_lang_reflect_Method_getExceptionTypes, "java.lang.reflect.Method_getExceptionTypes", (void *self)) {
    s_method *m = jcl_method_of(self);
    int32_t count = 0;
    if (m != NULL && m->exceptions != NULL && m->exceptions[0] != 0) {
        count = 1;
        for (const char *c = m->exceptions; *c; c++) if (*c == ',') count++;
    }
    void *arr = jcl_arr_ref(count);
    jcl_arr_set_type(arr, "[Ljava/lang/Class;");
    if (count > 0) {
        char *list = jcl_dup(m->exceptions);
        int32_t i = 0;
        for (char *name = list; name != NULL && i < count; i++) {
            char *comma = strchr(name, ',');
            if (comma != NULL) *comma = 0;
            jcl_arr_set(arr, i, jcl_class(name));
            name = comma != NULL ? comma + 1 : NULL;
        }
    }
    return arr;
}
BIND(void *, jcl_java_lang_reflect_Method_getGenericExceptionTypes, "java.lang.reflect.Method_getGenericExceptionTypes", (void *self)) {
    return jcl_java_lang_reflect_Method_getExceptionTypes(self);
}

/* Annotations of a method, a constructor or one of their parameters. */
static void *jcl_annotations_for(const char *cls, const char *owner) {
    const nabu_reflection_info *ri = nabu_lookup_reflection(cls);
    int32_t count = 0;
    for (int i = 0; ri != NULL && i < ri->annotation_count; i++) {
        if (ri->annotations[i].owner != NULL && strcmp(ri->annotations[i].owner, owner) == 0) count++;
    }
    void *arr = jcl_arr_ref(count);
    jcl_arr_set_type(arr, "[Ljava/lang/annotation/Annotation;");
    int32_t k = 0;
    for (int i = 0; ri != NULL && i < ri->annotation_count; i++) {
        if (ri->annotations[i].owner != NULL && strcmp(ri->annotations[i].owner, owner) == 0) {
            jcl_arr_set(arr, k++, jcl_ann_obj(&ri->annotations[i]));
        }
    }
    return arr;
}
void *jcl_executable_annotation(void *self, void *ann) {
    s_method *m = jcl_method_of(self);
    if (m == NULL) return NULL;
    const nabu_annotation_entry *e = nabu_get_field_annotation(m->cls, jcl_exec_annotation_owner(m), jcl_str(ann));
    return e != NULL ? jcl_ann_obj(e) : NULL;
}
BIND(void *, jcl_java_lang_reflect_Method_getAnnotation, "java.lang.reflect.Method_getAnnotation", (void *self, void *ann)) {
    return jcl_executable_annotation(self, ann);
}
BIND(void *, jcl_java_lang_reflect_Method_getDeclaredAnnotation, "java.lang.reflect.Method_getDeclaredAnnotation", (void *self, void *ann)) {
    return jcl_executable_annotation(self, ann);
}
BIND(int, jcl_java_lang_reflect_Method_isAnnotationPresent, "java.lang.reflect.Method_isAnnotationPresent", (void *self, void *ann)) {
    return jcl_executable_annotation(self, ann) != NULL;
}
BIND(void *, jcl_java_lang_reflect_Method_getDeclaredAnnotations, "java.lang.reflect.Method_getDeclaredAnnotations", (void *self)) {
    s_method *m = jcl_method_of(self);
    return m != NULL ? jcl_annotations_for(m->cls, jcl_exec_annotation_owner(m)) : jcl_arr_typed(0, "[Ljava/lang/annotation/Annotation;");
}
BIND(void *, jcl_java_lang_reflect_Method_getAnnotations, "java.lang.reflect.Method_getAnnotations", (void *self)) {
    return jcl_java_lang_reflect_Method_getDeclaredAnnotations(self);
}
BIND(void *, jcl_java_lang_reflect_Method_getAnnotationsByType, "java.lang.reflect.Method_getAnnotationsByType", (void *self, void *ann)) {
    void *found = jcl_executable_annotation(self, ann);
    void *arr = jcl_arr_typed(found != NULL ? 1 : 0, "[Ljava/lang/annotation/Annotation;");
    if (found != NULL) jcl_arr_set(arr, 0, found);
    return arr;
}
/* One array per parameter: the annotations of "name(descriptor)#i". */
BIND(void *, jcl_java_lang_reflect_Method_getParameterAnnotations, "java.lang.reflect.Method_getParameterAnnotations", (void *self)) {
    s_method *m = jcl_method_of(self);
    int32_t count = m != NULL && m->desc[0] == '(' ? jcl_exec_param_count(m) : 0;
    void *arr = jcl_arr_ref(count);
    jcl_arr_set_type(arr, "[[Ljava/lang/annotation/Annotation;");
    for (int32_t i = 0; i < count; i++) {
        char index[16];
        snprintf(index, sizeof index, "#%d", (int)i);
        jcl_arr_set(arr, i, jcl_annotations_for(m->cls, jcl_concat(jcl_exec_annotation_owner(m), index)));
    }
    return arr;
}
BIND(void *, jcl_java_lang_reflect_Method_getDefaultValue, "java.lang.reflect.Method_getDefaultValue", (void *self)) { (void)self; return NULL; }
BIND(int, jcl_java_lang_reflect_Method_trySetAccessible, "java.lang.reflect.Method_trySetAccessible", (void *self)) { (void)self; return 1; }
BIND(int, jcl_java_lang_reflect_Method_canAccess, "java.lang.reflect.Method_canAccess", (void *self, void *o)) { (void)self; (void)o; return 1; }
BIND(int, jcl_java_lang_reflect_Method_isAccessible, "java.lang.reflect.Method_isAccessible", (void *self)) { (void)self; return 1; }
/* As the JDK: equal declaring class, name and parameter types (here: the
 * descriptor); the hash is that of the class name xor that of the name. */
BIND(int, jcl_java_lang_reflect_Method_equals, "java.lang.reflect.Method_equals", (void *self, void *other)) {
    if (self == other) return 1;
    s_method *a = jcl_method_of(self);
    s_method *b = jcl_method_of(other);
    return a != NULL && b != NULL && strcmp(a->cls, b->cls) == 0 && strcmp(a->name, b->name) == 0
            && strcmp(a->desc, b->desc) == 0;
}
static int32_t jcl_string_hash(const char *s, int dots) {
    int32_t h = 0;
    for (; *s; s++) h = 31 * h + (dots && *s == '/' ? '.' : (unsigned char)*s);
    return h;
}
BIND(int32_t, jcl_java_lang_reflect_Method_hashCode, "java.lang.reflect.Method_hashCode", (void *self)) {
    s_method *m = jcl_method_of(self);
    if (m == NULL) return 0;
    /* A Constructor hashes its class name only, as the JDK. */
    return jcl_is_constructor(m) ? jcl_string_hash(m->cls, 1) : jcl_string_hash(m->cls, 1) ^ jcl_string_hash(m->name, 0);
}
BIND(void *, jcl_java_lang_reflect_Method_invoke, "java.lang.reflect.Method_invoke", (void *self, void *tgt, void *args)) { (void)self; (void)tgt; (void)args; return NULL; }
BIND(void, jcl_java_lang_reflect_Method_setAccessible, "java.lang.reflect.Method_setAccessible", (void *self, int v)) { (void)self; (void)v; }
/* As the JDK: "public abstract java.lang.String a.I.m(int,java.lang.Object)",
 * "public a.B(int)" for a constructor. */
static void * jcl_java_lang_reflect_Method_toString (void *self) {
    s_method *m = jcl_method_of(self);
    if (m == NULL) return (void *)"";
    const char *r = strchr(m->desc, ')');
    char *cls = jcl_dup(m->cls);
    for (char *c = cls; *c; c++) if (*c == '/') *c = '.';
    /* Modifier words in the order of java.lang.reflect.Modifier.toString. */
    static const struct { int32_t bit; const char *word; } words[] = {
        {0x0001, "public "}, {0x0004, "protected "}, {0x0002, "private "}, {0x0400, "abstract "},
        {0x0008, "static "}, {0x0010, "final "}, {0x0020, "synchronized "}, {0x0100, "native "}
    };
    const int32_t mods = m->modifiers;
    char *out = jcl_dup("");
    for (size_t i = 0; i < sizeof words / sizeof words[0]; i++) {
        if ((mods & words[i].bit) != 0) out = jcl_concat(out, words[i].word);
    }
    if ((mods & JCL_INTERFACE_OWNER) != 0 && (mods & (0x0001 | 0x0008 | 0x0400)) == 0x0001) {
        out = jcl_concat(out, "default ");
    }
    if (jcl_is_constructor(m)) {
        out = jcl_concat(out, cls);
    } else {
        out = jcl_concat(out, r != NULL ? jcl_desc_type_name(r + 1, strlen(r + 1)) : "void");
        out = jcl_concat(jcl_concat(jcl_concat(jcl_concat(out, " "), cls), "."), m->name);
    }
    out = jcl_concat(out, "(");
    int first = 1;
    for (const char *d = m->desc + 1; *d != ')' && *d != 0; d += jcl_desc_len(d)) {
        if (!first) out = jcl_concat(out, ",");
        out = jcl_concat(out, jcl_desc_type_name(d, jcl_desc_len(d)));
        first = 0;
    }
    out = jcl_concat(out, ")");
    if (m->exceptions != NULL && m->exceptions[0] != 0) {
        char *list = jcl_dup(m->exceptions);
        for (char *c = list; *c; c++) if (*c == '/') *c = '.';
        out = jcl_concat(jcl_concat(out, " throws "), list);
    }
    return (void *)out;
}
BIND(void *, jcl_java_lang_reflect_Method_toString_jstr, "java.lang.reflect.Method_toString", (void *self)) {
    void *r = (void *)jcl_java_lang_reflect_Method_toString(self);
    return r == NULL ? NULL : nabu_jstring_new(nabu_cstr(r));
}
static void * jcl_java_lang_reflect_Method_toGenericString (void *self) { return jcl_java_lang_reflect_Method_toString(self); }
BIND(void *, jcl_java_lang_reflect_Method_toGenericString_jstr, "java.lang.reflect.Method_toGenericString", (void *self)) {
    void *r = (void *)jcl_java_lang_reflect_Method_toGenericString(self);
    return r == NULL ? NULL : nabu_jstring_new(nabu_cstr(r));
}

/* The same members under Constructor and Executable. A Constructor without
 * K_METHOD state (the no-argument constructor of the C runtime's table)
 * answers as one without parameters. Each symbol is spelled out: the
 * backend recognizes the runtime's symbols by their string literals. */
#define JCL_ALIAS1(ret, cname, sym, target) BIND(ret, cname, sym, (void *self)) { return target(self); }
#define JCL_ALIAS2(ret, cname, sym, target) BIND(ret, cname, sym, (void *self, void *arg)) { return target(self, arg); }
JCL_ALIAS1(void *, jcl_ctor_getName, "java.lang.reflect.Constructor_getName", jcl_java_lang_reflect_Method_getName_jstr)
JCL_ALIAS1(void *, jcl_ctor_getParameterTypes, "java.lang.reflect.Constructor_getParameterTypes", jcl_java_lang_reflect_Method_getParameterTypes)
JCL_ALIAS1(int32_t, jcl_ctor_getParameterCount, "java.lang.reflect.Constructor_getParameterCount", jcl_java_lang_reflect_Method_getParameterCount)
JCL_ALIAS1(void *, jcl_ctor_getGenericParameterTypes, "java.lang.reflect.Constructor_getGenericParameterTypes", jcl_java_lang_reflect_Method_getGenericParameterTypes)
JCL_ALIAS1(int32_t, jcl_ctor_getModifiers, "java.lang.reflect.Constructor_getModifiers", jcl_java_lang_reflect_Method_getModifiers)
JCL_ALIAS1(int, jcl_ctor_isVarArgs, "java.lang.reflect.Constructor_isVarArgs", jcl_java_lang_reflect_Method_isVarArgs)
JCL_ALIAS1(int, jcl_ctor_isSynthetic, "java.lang.reflect.Constructor_isSynthetic", jcl_java_lang_reflect_Method_isSynthetic)
JCL_ALIAS1(void *, jcl_ctor_getExceptionTypes, "java.lang.reflect.Constructor_getExceptionTypes", jcl_java_lang_reflect_Method_getExceptionTypes)
JCL_ALIAS1(void *, jcl_ctor_getGenericExceptionTypes, "java.lang.reflect.Constructor_getGenericExceptionTypes", jcl_java_lang_reflect_Method_getExceptionTypes)
JCL_ALIAS1(void *, jcl_ctor_getTypeParameters, "java.lang.reflect.Constructor_getTypeParameters", jcl_java_lang_reflect_Method_getTypeParameters)
JCL_ALIAS1(void *, jcl_ctor_getParameterAnnotations, "java.lang.reflect.Constructor_getParameterAnnotations", jcl_java_lang_reflect_Method_getParameterAnnotations)
JCL_ALIAS1(void *, jcl_ctor_getDeclaredAnnotations, "java.lang.reflect.Constructor_getDeclaredAnnotations", jcl_java_lang_reflect_Method_getDeclaredAnnotations)
JCL_ALIAS1(void *, jcl_ctor_getAnnotations, "java.lang.reflect.Constructor_getAnnotations", jcl_java_lang_reflect_Method_getDeclaredAnnotations)
JCL_ALIAS2(void *, jcl_ctor_getAnnotation, "java.lang.reflect.Constructor_getAnnotation", jcl_executable_annotation)
JCL_ALIAS2(int, jcl_ctor_isAnnotationPresent, "java.lang.reflect.Constructor_isAnnotationPresent", jcl_java_lang_reflect_Method_isAnnotationPresent)
JCL_ALIAS1(void *, jcl_ctor_toString, "java.lang.reflect.Constructor_toString", jcl_java_lang_reflect_Method_toString_jstr)
JCL_ALIAS1(void *, jcl_ctor_toGenericString, "java.lang.reflect.Constructor_toGenericString", jcl_java_lang_reflect_Method_toString_jstr)
JCL_ALIAS2(int, jcl_ctor_equals, "java.lang.reflect.Constructor_equals", jcl_java_lang_reflect_Method_equals)
JCL_ALIAS1(int32_t, jcl_ctor_hashCode, "java.lang.reflect.Constructor_hashCode", jcl_java_lang_reflect_Method_hashCode)
JCL_ALIAS1(int, jcl_ctor_trySetAccessible, "java.lang.reflect.Constructor_trySetAccessible", jcl_java_lang_reflect_Method_trySetAccessible)
JCL_ALIAS2(int, jcl_ctor_canAccess, "java.lang.reflect.Constructor_canAccess", jcl_java_lang_reflect_Method_canAccess)
/* The declaring class: from the record, or from the C runtime's table entry. */
BIND(void *, jcl_java_lang_reflect_Constructor_getDeclaringClass, "java.lang.reflect.Constructor_getDeclaringClass", (void *self)) {
    if (jcl_method_of(self) != NULL) return jcl_java_lang_reflect_Method_getDeclaringClass(self);
    s_ctor *st = (s_ctor *)jcl_state_kind(self, K_CTOR);
    return jcl_class(st != NULL ? st->e->name : "");
}
JCL_ALIAS1(void *, jcl_exec_getName, "java.lang.reflect.Executable_getName", jcl_java_lang_reflect_Method_getName_jstr)
JCL_ALIAS1(void *, jcl_exec_getParameterTypes, "java.lang.reflect.Executable_getParameterTypes", jcl_java_lang_reflect_Method_getParameterTypes)
JCL_ALIAS1(int32_t, jcl_exec_getParameterCount, "java.lang.reflect.Executable_getParameterCount", jcl_java_lang_reflect_Method_getParameterCount)
JCL_ALIAS1(void *, jcl_exec_getGenericParameterTypes, "java.lang.reflect.Executable_getGenericParameterTypes", jcl_java_lang_reflect_Method_getGenericParameterTypes)
JCL_ALIAS1(int32_t, jcl_exec_getModifiers, "java.lang.reflect.Executable_getModifiers", jcl_java_lang_reflect_Method_getModifiers)
JCL_ALIAS1(int, jcl_exec_isVarArgs, "java.lang.reflect.Executable_isVarArgs", jcl_java_lang_reflect_Method_isVarArgs)
JCL_ALIAS1(void *, jcl_exec_getExceptionTypes, "java.lang.reflect.Executable_getExceptionTypes", jcl_java_lang_reflect_Method_getExceptionTypes)
JCL_ALIAS1(void *, jcl_exec_getParameterAnnotations, "java.lang.reflect.Executable_getParameterAnnotations", jcl_java_lang_reflect_Method_getParameterAnnotations)
JCL_ALIAS1(void *, jcl_exec_getDeclaringClass, "java.lang.reflect.Executable_getDeclaringClass", jcl_java_lang_reflect_Constructor_getDeclaringClass)
JCL_ALIAS1(void *, jcl_exec_getDeclaredAnnotations, "java.lang.reflect.Executable_getDeclaredAnnotations", jcl_java_lang_reflect_Method_getDeclaredAnnotations)
JCL_ALIAS2(void *, jcl_exec_getAnnotation, "java.lang.reflect.Executable_getAnnotation", jcl_executable_annotation)
/* AccessibleObject's annotation methods (the frontend names the declaring
 * class): a field's, a method's or a constructor's annotations. */
BIND(void *, jcl_java_lang_reflect_AccessibleObject_getDeclaredAnnotations, "java.lang.reflect.AccessibleObject_getDeclaredAnnotations", (void *self)) {
    s_field *f = jcl_field_of(self);
    if (f != NULL) return jcl_annotations_for(f->cls, f->f->name);
    return jcl_java_lang_reflect_Method_getDeclaredAnnotations(self);
}
BIND(void *, jcl_java_lang_reflect_AccessibleObject_getAnnotations, "java.lang.reflect.AccessibleObject_getAnnotations", (void *self)) {
    return jcl_java_lang_reflect_AccessibleObject_getDeclaredAnnotations(self);
}
BIND(void *, jcl_java_lang_reflect_AccessibleObject_getDeclaredAnnotation, "java.lang.reflect.AccessibleObject_getDeclaredAnnotation", (void *self, void *ann)) {
    return jcl_java_lang_reflect_AccessibleObject_getAnnotation(self, ann);
}
BIND(void *, jcl_java_lang_reflect_AccessibleObject_getAnnotationsByType, "java.lang.reflect.AccessibleObject_getAnnotationsByType", (void *self, void *ann)) {
    void *found = jcl_java_lang_reflect_AccessibleObject_getAnnotation(self, ann);
    void *arr = jcl_arr_typed(found != NULL ? 1 : 0, "[Ljava/lang/annotation/Annotation;");
    if (found != NULL) jcl_arr_set(arr, 0, found);
    return arr;
}
/* All annotations of a field (its owner in the metadata is its name). */
BIND(void *, jcl_java_lang_reflect_Field_getDeclaredAnnotations, "java.lang.reflect.Field_getDeclaredAnnotations", (void *self)) {
    s_field *f = jcl_field_of(self);
    return f != NULL ? jcl_annotations_for(f->cls, f->f->name) : jcl_arr_typed(0, "[Ljava/lang/annotation/Annotation;");
}
BIND(void *, jcl_java_lang_reflect_Field_getAnnotations, "java.lang.reflect.Field_getAnnotations", (void *self)) {
    return jcl_java_lang_reflect_Field_getDeclaredAnnotations(self);
}
/* toString of the objects this runtime makes (Method, Field, Class, ...).
 * They carry a copy of java.lang.Object's vtable (nabu_set_library_vtable)
 * whose toString slot is this function: a virtual toString() or "" + obj
 * then gives what the JDK gives instead of "java.lang.reflect.Method@1f". */
static void *(*jcl_object_tostring)(void *) = NULL;

static void *jcl_library_tostring(void *self) {
    if (jcl_state_kind(self, K_METHOD) != NULL) {
        return jcl_java_lang_reflect_Method_toString_jstr(self);
    }
    if (jcl_state_kind(self, K_FIELD) != NULL) {
        s_field *f = jcl_field_of(self);
        char *cls = jcl_dup(f->cls);
        for (char *c = cls; *c; c++) if (*c == '/') *c = '.';
        const char *type = f->f->type_desc;
        char *out = jcl_concat(jcl_desc_type_name(type, strlen(type)), " ");
        return nabu_jstring_new(jcl_concat(jcl_concat(jcl_concat(out, cls), "."), f->f->name));
    }
    if (jcl_state_kind(self, K_CLASSOBJ) != NULL) {
        return nabu_jstring_new(jcl_concat("class ", jcl_binary_name(jcl_str(self))));
    }
    if (jcl_state_kind(self, K_GTYPE) != NULL) {
        return jcl_gtype_name_jstr(self);
    }
    if (jcl_is_executable(self)) {
        return jcl_java_lang_reflect_Method_toString_jstr(self);
    }
    return jcl_object_tostring != NULL ? jcl_object_tostring(self) : NULL;
}

/* Called by the backend instead of nabu_set_object_vtable when it knows
 * Object.toString's slot: the runtime's objects get a copy of Object's
 * vtable with jcl_library_tostring in that slot. */
void nabu_set_library_vtable(void **vtable, int32_t length, int32_t tostring_slot) {
    if (vtable == NULL || tostring_slot < 0 || tostring_slot >= length) {
        nabu_set_object_vtable(vtable);
        return;
    }
    void **copy = (void **)malloc(sizeof(void *) * (size_t)length);
    if (copy == NULL) {
        nabu_set_object_vtable(vtable);
        return;
    }
    memcpy(copy, vtable, sizeof(void *) * (size_t)length);
    jcl_object_tostring = (void *(*)(void *))vtable[tostring_slot];
    copy[tostring_slot] = (void *)jcl_library_tostring;
    nabu_set_object_vtable(copy);
}

BIND(void *, jcl_java_lang_reflect_Proxy_getInvocationHandler, "java.lang.reflect.Proxy_getInvocationHandler", (void *self, void *p)) { (void)self; (void)p; return NULL; }
BIND(int, jcl_java_lang_reflect_Proxy_isProxyClass, "java.lang.reflect.Proxy_isProxyClass", (void *self)) { (void)self; return 0; }
BIND(void *, jcl_java_lang_reflect_Proxy_newProxyInstance, "java.lang.reflect.Proxy_newProxyInstance", (void *cl, void *ifs, void *h)) { (void)cl; (void)ifs; (void)h; return NULL; }





/* ==SECTIE== */

/* ---------------------------------------------------------------
 * JDK-natives die de bytecode van java.lang nodig heeft om zijn eigen
 * statische initializers te draaien (Boolean.TYPE, Double-bitconversies, ...).
 * --------------------------------------------------------------- */
BIND(void *, jcl_java_lang_Class_getPrimitiveClass, "java.lang.Class_getPrimitiveClass", (void *name)) {
    return jcl_class(jcl_str(name));
}
/* Assertions staan uit, net als bij een JVM zonder -ea. */
BIND(int, jcl_java_lang_Class_desiredAssertionStatus0, "java.lang.Class_desiredAssertionStatus0", (void *cls)) { (void)cls; return 0; }
BIND(int, jcl_java_lang_Class_isHidden, "java.lang.Class_isHidden", (void *self)) { (void)self; return 0; }
BIND(int, jcl_java_lang_Class_isRecord0, "java.lang.Class_isRecord0", (void *self)) { (void)self; return 0; }
BIND(int64_t, jcl_java_lang_Double_doubleToRawLongBits, "java.lang.Double_doubleToRawLongBits", (double v)) { int64_t r; memcpy(&r, &v, 8); return r; }
BIND(double, jcl_java_lang_Double_longBitsToDouble, "java.lang.Double_longBitsToDouble", (int64_t v)) { double r; memcpy(&r, &v, 8); return r; }
BIND(int32_t, jcl_java_lang_Float_floatToRawIntBits, "java.lang.Float_floatToRawIntBits", (float v)) { int32_t r; memcpy(&r, &v, 4); return r; }
BIND(float, jcl_java_lang_Float_intBitsToFloat, "java.lang.Float_intBitsToFloat", (int32_t v)) { float r; memcpy(&r, &v, 4); return r; }
BIND(void *, jcl_java_lang_String_intern, "java.lang.String_intern", (void *self)) { return self; }
BIND(int32_t, jcl_java_lang_Runtime_availableProcessors, "java.lang.Runtime_availableProcessors", (void *self)) { (void)self; return 1; }
BIND(void, jcl_java_lang_Runtime_gc, "java.lang.Runtime_gc", (void *self)) {
    (void)self;
#ifdef NABU_GC_BOEHM
    GC_gcollect();
    GC_invoke_finalizers();
#endif
}
BIND(int, jcl_java_lang_Class_desiredAssertionStatus, "java.lang.Class_desiredAssertionStatus", (void *self)) { (void)self; return 0; }
/* Arrays.copyOf(Object[], int): de JDK-versie vraagt original.getClass() op,
 * maar een array heeft hier geen objectheader (lengte op 0, data op 8). */
BIND(void *, jcl_java_util_Arrays_copyOf_refs, "java.util.Arrays_copyOf$P7b09cd37I32", (void *original, int32_t new_length)) {
    if (new_length < 0) new_length = 0;
    void *copy = jcl_arr_ref(new_length);
    int32_t n = jcl_arr_len(original);
    if (n > new_length) n = new_length;
    if (n > 0) memcpy((char *)copy + 8, (char *)original + 8, (size_t)n * sizeof(void *));
    /* Same class as the original (Arrays.copyOf(T[], int) keeps the type). */
    if (original != NULL) ((int32_t *)copy)[1] = ((int32_t *)original)[1];
    return copy;
}
/* StackTraceSupport.capture0(int max): the return addresses of the calling frames as a long[]. */
BIND(void *, jcl_stack_capture0, "io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime.StackTraceSupport_capture0",
     (int32_t max)) {
    uintptr_t frames[256];
    int n = nabu_capture_frames(frames, max);
    void *arr = jcl_alloc(8 + (size_t)n * sizeof(int64_t));
    ((int32_t *)arr)[0] = n;
    ((int32_t *)arr)[1] = 8;
    nabu_array_tag(arr, "[J");
    for (int i = 0; i < n; i++) ((int64_t *)((char *)arr + 8))[i] = (int64_t)frames[i];
    return arr;
}
/* StackTraceSupport.frameName0(long address): the symbol of the function, or null. */
BIND(void *, jcl_stack_frameName0, "io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime.StackTraceSupport_frameName0",
     (int64_t address)) {
    const char *name = nabu_frame_name((uintptr_t)address);
    if (getenv("NABU_TRACE_FRAMES") != NULL) fprintf(stderr, "frame %p %s\n", (void *)(uintptr_t)address, name != NULL ? name : "?");
    return name != NULL ? nabu_jstring_new(name) : NULL;
}
/* StackTraceSupport.frameLocation0(long address): "scope-symbol<TAB>file<TAB>line" of the source line of the
 * address, or null when the function has no line table. The scope is the function the line belongs to. */
BIND(void *, jcl_stack_frameLocation0, "io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime.StackTraceSupport_frameLocation0",
     (int64_t address)) {
    const char *scope = NULL, *file = NULL;
    int line = nabu_frame_line((uintptr_t)address, &scope, &file);
    if (line <= 0 || file == NULL) return NULL;
    char num[16];
    snprintf(num, sizeof num, "%d", line);
    return nabu_jstring_new(jcl_concat(jcl_concat(jcl_concat(jcl_concat(scope != NULL ? scope : "", "\t"), file), "\t"), num));
}

/* ---------------------------------------------------------------
 * jdk.internal.misc.Unsafe-natives
 *
 * Unsafe benadert geheugen op (object, offset). Arrays hebben hier de lengte
 * op offset 0 en de data op offset 8; de schaal volgt uit het elementtype.
 * De compare-and-set-varianten zijn niet atomair: de runtime is
 * single-threaded voor gecompileerde code.
 * --------------------------------------------------------------- */
static const char *jcl_unsafe_class_name(void *cls) {
    if (cls == NULL) return "";
    if (nabu_jcl_is_object(cls)) {
        s_str *st = (s_str *)nabu_jcl_state(cls);
        return st != NULL && st->v != NULL ? st->v : "";
    }
    return nabu_cstr(cls);
}

#define UNSAFE_ADDR(o, off) ((char *)(o) + (off))

BIND(int32_t, jcl_Unsafe_arrayBaseOffset0, "jdk.internal.misc.Unsafe_arrayBaseOffset0", (void *self, void *cls)) { (void)self; (void)cls; return 8; }
BIND(int32_t, jcl_Unsafe_arrayIndexScale0, "jdk.internal.misc.Unsafe_arrayIndexScale0", (void *self, void *cls)) {
    (void)self;
    const char *n = jcl_unsafe_class_name(cls);
    const char *b = strchr(n, '[');
    char e = b != NULL ? b[1] : 'L';
    switch (e) {
        case 'B': case 'Z': return 1;
        case 'C': case 'S': return 2;
        case 'I': case 'F': return 4;
        default: return 8;
    }
}
BIND(int64_t, jcl_Unsafe_objectFieldOffset1, "jdk.internal.misc.Unsafe_objectFieldOffset1", (void *self, void *cls, void *name)) {
    (void)self;
    const char *cls_name = jcl_unsafe_class_name(cls);
    const char *field = nabu_cstr(name);
    /* java.lang.Thread is not compiled: its Unsafe-reached fields (ThreadLocalRandom's seed and probes) live in
     * the room behind the thread object. */
    if (strcmp(cls_name, "java.lang.Thread") == 0 || strcmp(cls_name, "java/lang/Thread") == 0) {
        if (strcmp(field, "threadLocalRandomSeed") == 0) return 56;
        if (strcmp(field, "threadLocalRandomProbe") == 0) return 64;
        if (strcmp(field, "threadLocalRandomSecondarySeed") == 0) return 68;
        return 72;
    }
    /* Eerst de veldtabel van de gecompileerde klasse (en haar supers): dekt
     * elke uit bytecode gecompileerde JDK-klasse, bv. ConcurrentHashMap's
     * SIZECTL. De reflectie-registry kent alleen reflect-config-klassen. */
    char internal[512];
    size_t n = strlen(cls_name);
    if (n < sizeof internal) {
        for (size_t i = 0; i <= n; i++) internal[i] = cls_name[i] == '.' ? '/' : cls_name[i];
        for (const nabu_type_info *ti = nabu_compiled_type_for(internal); ti != NULL; ti = ti->super_type) {
            for (int i = 0; i < ti->instance_field_count; i++) {
                if (strcmp(ti->instance_fields[i].name, field) == 0) {
                    return (int64_t)ti->instance_fields[i].offset;
                }
            }
        }
    }
    return (int64_t)nabu_reflect_field_offset(cls_name, field);
}
/* Unsafe.objectFieldOffset(Field) after its checks: the offset the Field object carries. */
BIND(int64_t, jcl_Unsafe_objectFieldOffset0, "jdk.internal.misc.Unsafe_objectFieldOffset0", (void *self, void *field)) {
    (void)self;
    s_field *f = jcl_field_of(field);
    return f != NULL && f->f->static_address == NULL ? (int64_t)f->f->offset : -1;
}
/* A static field has no base object: its offset is its address (Unsafe adds the two). */
BIND(int64_t, jcl_Unsafe_staticFieldOffset0, "jdk.internal.misc.Unsafe_staticFieldOffset0", (void *self, void *field)) {
    (void)self;
    s_field *f = jcl_field_of(field);
    return f != NULL && f->f->static_address != NULL ? (int64_t)(intptr_t)f->f->static_address : -1;
}
BIND(void *, jcl_Unsafe_staticFieldBase0, "jdk.internal.misc.Unsafe_staticFieldBase0", (void *self, void *field)) {
    (void)self; (void)field;
    return NULL;
}
BIND(void, jcl_Unsafe_fullFence, "jdk.internal.misc.Unsafe_fullFence", (void *self)) { (void)self; }
BIND(void, jcl_Unsafe_ensureClassInitialized0, "jdk.internal.misc.Unsafe_ensureClassInitialized0", (void *self, void *cls)) { (void)self; (void)cls; }
BIND(int, jcl_Unsafe_shouldBeInitialized0, "jdk.internal.misc.Unsafe_shouldBeInitialized0", (void *self, void *cls)) { (void)self; (void)cls; return 0; }


BIND(int8_t, jcl_Unsafe_getByte, "jdk.internal.misc.Unsafe_getByte", (void *self, void *o, int64_t off)) { (void)self; int8_t v; memcpy(&v, UNSAFE_ADDR(o, off), sizeof v); return v; }
BIND(int16_t, jcl_Unsafe_getShort, "jdk.internal.misc.Unsafe_getShort", (void *self, void *o, int64_t off)) { (void)self; int16_t v; memcpy(&v, UNSAFE_ADDR(o, off), sizeof v); return v; }
BIND(uint16_t, jcl_Unsafe_getChar, "jdk.internal.misc.Unsafe_getChar", (void *self, void *o, int64_t off)) { (void)self; uint16_t v; memcpy(&v, UNSAFE_ADDR(o, off), sizeof v); return v; }
BIND(int32_t, jcl_Unsafe_getInt, "jdk.internal.misc.Unsafe_getInt", (void *self, void *o, int64_t off)) { (void)self; int32_t v; memcpy(&v, UNSAFE_ADDR(o, off), sizeof v); return v; }
BIND(int32_t, jcl_Unsafe_getIntVolatile, "jdk.internal.misc.Unsafe_getIntVolatile", (void *self, void *o, int64_t off)) { (void)self; int32_t v; memcpy(&v, UNSAFE_ADDR(o, off), sizeof v); return v; }
BIND(int64_t, jcl_Unsafe_getLong, "jdk.internal.misc.Unsafe_getLong", (void *self, void *o, int64_t off)) { (void)self; int64_t v; memcpy(&v, UNSAFE_ADDR(o, off), sizeof v); return v; }
BIND(int64_t, jcl_Unsafe_getLongVolatile, "jdk.internal.misc.Unsafe_getLongVolatile", (void *self, void *o, int64_t off)) { (void)self; int64_t v; memcpy(&v, UNSAFE_ADDR(o, off), sizeof v); return v; }
BIND(void *, jcl_Unsafe_getReference, "jdk.internal.misc.Unsafe_getReference", (void *self, void *o, int64_t off)) { (void)self; void * v; memcpy(&v, UNSAFE_ADDR(o, off), sizeof v); return v; }
BIND(void *, jcl_Unsafe_getReferenceVolatile, "jdk.internal.misc.Unsafe_getReferenceVolatile", (void *self, void *o, int64_t off)) { (void)self; void * v; memcpy(&v, UNSAFE_ADDR(o, off), sizeof v); return v; }
BIND(void, jcl_Unsafe_putByte, "jdk.internal.misc.Unsafe_putByte", (void *self, void *o, int64_t off, int8_t v)) { (void)self; memcpy(UNSAFE_ADDR(o, off), &v, sizeof v); }
BIND(void, jcl_Unsafe_putShort, "jdk.internal.misc.Unsafe_putShort", (void *self, void *o, int64_t off, int16_t v)) { (void)self; memcpy(UNSAFE_ADDR(o, off), &v, sizeof v); }
BIND(void, jcl_Unsafe_putInt, "jdk.internal.misc.Unsafe_putInt", (void *self, void *o, int64_t off, int32_t v)) { (void)self; memcpy(UNSAFE_ADDR(o, off), &v, sizeof v); }
BIND(void, jcl_Unsafe_putIntVolatile, "jdk.internal.misc.Unsafe_putIntVolatile", (void *self, void *o, int64_t off, int32_t v)) { (void)self; memcpy(UNSAFE_ADDR(o, off), &v, sizeof v); }
BIND(void, jcl_Unsafe_putLong, "jdk.internal.misc.Unsafe_putLong", (void *self, void *o, int64_t off, int64_t v)) { (void)self; memcpy(UNSAFE_ADDR(o, off), &v, sizeof v); }
BIND(void, jcl_Unsafe_putLongVolatile, "jdk.internal.misc.Unsafe_putLongVolatile", (void *self, void *o, int64_t off, int64_t v)) { (void)self; memcpy(UNSAFE_ADDR(o, off), &v, sizeof v); }
BIND(void, jcl_Unsafe_putFloat, "jdk.internal.misc.Unsafe_putFloat", (void *self, void *o, int64_t off, float v)) { (void)self; memcpy(UNSAFE_ADDR(o, off), &v, sizeof v); }
BIND(void, jcl_Unsafe_putDouble, "jdk.internal.misc.Unsafe_putDouble", (void *self, void *o, int64_t off, double v)) { (void)self; memcpy(UNSAFE_ADDR(o, off), &v, sizeof v); }
BIND(void, jcl_Unsafe_putReference, "jdk.internal.misc.Unsafe_putReference", (void *self, void *o, int64_t off, void * v)) { (void)self; memcpy(UNSAFE_ADDR(o, off), &v, sizeof v); }
BIND(void, jcl_Unsafe_putReferenceVolatile, "jdk.internal.misc.Unsafe_putReferenceVolatile", (void *self, void *o, int64_t off, void * v)) { (void)self; memcpy(UNSAFE_ADDR(o, off), &v, sizeof v); }
BIND(void, jcl_Unsafe_putBoolean, "jdk.internal.misc.Unsafe_putBoolean", (void *self, void *o, int64_t off, int v)) { (void)self; *(int8_t *)UNSAFE_ADDR(o, off) = (int8_t)(v != 0); }
BIND(void, jcl_Unsafe_putChar, "jdk.internal.misc.Unsafe_putChar$Pa86c5f3aP5f790d9cI64I16", (void *self, void *o, int64_t off, uint16_t v)) { (void)self; memcpy(UNSAFE_ADDR(o, off), &v, sizeof v); }


BIND(int, jcl_Unsafe_compareAndSetInt, "jdk.internal.misc.Unsafe_compareAndSetInt", (void *self, void *o, int64_t off, int32_t expected, int32_t x)) { (void)self; int32_t cur; memcpy(&cur, UNSAFE_ADDR(o, off), sizeof cur); if (cur != expected) return 0; memcpy(UNSAFE_ADDR(o, off), &x, sizeof x); return 1; }
BIND(int, jcl_Unsafe_compareAndSetLong, "jdk.internal.misc.Unsafe_compareAndSetLong", (void *self, void *o, int64_t off, int64_t expected, int64_t x)) { (void)self; int64_t cur; memcpy(&cur, UNSAFE_ADDR(o, off), sizeof cur); if (cur != expected) return 0; memcpy(UNSAFE_ADDR(o, off), &x, sizeof x); return 1; }
BIND(int, jcl_Unsafe_compareAndSetReference, "jdk.internal.misc.Unsafe_compareAndSetReference", (void *self, void *o, int64_t off, void * expected, void * x)) { (void)self; void * cur; memcpy(&cur, UNSAFE_ADDR(o, off), sizeof cur); if (cur != expected) return 0; memcpy(UNSAFE_ADDR(o, off), &x, sizeof x); return 1; }
BIND(int64_t, jcl_Unsafe_compareAndExchangeLong, "jdk.internal.misc.Unsafe_compareAndExchangeLong", (void *self, void *o, int64_t off, int64_t expected, int64_t x)) { (void)self; int64_t cur; memcpy(&cur, UNSAFE_ADDR(o, off), sizeof cur); if (cur == expected) memcpy(UNSAFE_ADDR(o, off), &x, sizeof x); return cur; }
BIND(void *, jcl_Unsafe_compareAndExchangeReference, "jdk.internal.misc.Unsafe_compareAndExchangeReference", (void *self, void *o, int64_t off, void * expected, void * x)) { (void)self; void * cur; memcpy(&cur, UNSAFE_ADDR(o, off), sizeof cur); if (cur == expected) memcpy(UNSAFE_ADDR(o, off), &x, sizeof x); return cur; }
BIND(int64_t, jcl_Unsafe_allocateMemory0, "jdk.internal.misc.Unsafe_allocateMemory0", (void *self, int64_t bytes)) { (void)self; return (int64_t)(intptr_t)malloc((size_t)bytes); }
BIND(void, jcl_Unsafe_freeMemory0, "jdk.internal.misc.Unsafe_freeMemory0", (void *self, int64_t address)) { (void)self; free((void *)(intptr_t)address); }
BIND(void, jcl_Unsafe_setMemory0, "jdk.internal.misc.Unsafe_setMemory0", (void *self, void *o, int64_t off, int64_t bytes, int8_t value)) { (void)self; memset(UNSAFE_ADDR(o, off), value, (size_t)bytes); }
BIND(void, jcl_Unsafe_copyMemory0, "jdk.internal.misc.Unsafe_copyMemory0", (void *self, void *src, int64_t src_off, void *dst, int64_t dst_off, int64_t bytes)) { (void)self; memmove(UNSAFE_ADDR(dst, dst_off), UNSAFE_ADDR(src, src_off), (size_t)bytes); }
/* jdk.internal.misc.VM: er is geen VM-opstart (System.initPhase1), dus er
 * zijn geen bewaarde systeemeigenschappen. getSavedProperty geeft null,
 * waarop de JDK-code zijn defaults gebruikt (bv. de Integer-cache t/m 127). */
BIND(void, jcl_jdk_internal_misc_VM_initialize, "jdk.internal.misc.VM_initialize", (void)) { }
BIND(void *, jcl_jdk_internal_misc_VM_getSavedProperty, "jdk.internal.misc.VM_getSavedProperty", (void *key)) { (void)key; return NULL; }

/* ---------------------------------------------------------------
 * VM-gebonden natives en methoden van klassen buiten de bytecode-sluiting
 * (Thread, Runtime, System, Class) die de JDK-bytecode tijdens gewoon
 * gebruik aanroept. De runtime is single-threaded voor gecompileerde code:
 * er is precies een thread ("main") en locks zijn altijd vrij.
 * --------------------------------------------------------------- */
BIND(void, jcl_CDS_initializeFromArchive, "jdk.internal.misc.CDS_initializeFromArchive", (void *cls)) { (void)cls; }
BIND(int32_t, jcl_CDS_getCDSConfigStatus, "jdk.internal.misc.CDS_getCDSConfigStatus", (void)) { return 0; }
BIND(int64_t, jcl_CDS_getRandomSeedForDumping, "jdk.internal.misc.CDS_getRandomSeedForDumping", (void)) { return 0; }
BIND(int, jcl_CDS_needsClassInitBarrier0, "jdk.internal.misc.CDS_needsClassInitBarrier0", (void *cls)) { (void)cls; return 0; }
BIND(void, jcl_CDS_logLambdaFormInvoker, "jdk.internal.misc.CDS_logLambdaFormInvoker", (void *line)) { (void)line; }

static void *jcl_new_thread(const char *name, void *target) {
    static int64_t next_id = 1;
    s_thread *st = (s_thread *)jcl_alloc(sizeof(s_thread));
    st->kind = K_THREAD;
    st->name = jcl_dup(name);
    st->target = target;
    st->priority = 5;
    st->id = next_id++;
    /* Room behind the object for the fields the JDK reaches with Unsafe (see JCL_THREAD_FIELD). */
    void *obj = jcl_alloc(48 + 8 + 64);
    nabu_init_object(obj, "java/lang/Thread");
    nabu_jcl_set_state(obj, st);
    return obj;
}

static void *jcl_main_thread(void) {
    static void *thread = NULL;
    if (thread == NULL) thread = jcl_new_thread("main", NULL);
    return thread;
}

/* The Thread object of the running thread: the thread's own, or "main". */
static __thread void *jcl_cur_thread = NULL;
static void *jcl_current_thread(void) { return jcl_cur_thread != NULL ? jcl_cur_thread : jcl_main_thread(); }

static s_thread *jcl_thread_of(void *self) {
    s_thread *st = (s_thread *)jcl_state_kind(self, K_THREAD);
    if (st == NULL) st = (s_thread *)jcl_state_kind(jcl_current_thread(), K_THREAD);
    return st;
}

/* new Thread(...): the arguments are told apart by what they are, a String is the name and the first other
 * object (after a ThreadGroup in front of three or more) the Runnable. The backend calls Thread_init$<n>. */
static void jcl_thread_construct(void *self, int n, void **args) {
    s_thread *st = (s_thread *)jcl_alloc(sizeof(s_thread));
    static int64_t next_id = 2;
    st->kind = K_THREAD;
    st->priority = 5;
    st->id = next_id++;
    const char *name = NULL;
    for (int i = n >= 3 ? 1 : 0; i < n && i < 3; i++) {
        if (args[i] == NULL) continue;
        if (nabu_is_jstring(args[i]) || (!nabu_jcl_is_object(args[i]) && nabu_compiled_type_name(args[i]) == NULL)
                || jcl_state_kind(args[i], K_STR) != NULL) name = nabu_cstr(args[i]);
        else if (st->target == NULL) st->target = args[i];
    }
    if (name != NULL) {
        st->name = jcl_dup(name);
    } else {
        char generated[32];
        static int generated_count = 0;
        snprintf(generated, sizeof generated, "Thread-%d", generated_count++);
        st->name = jcl_dup(generated);
    }
    nabu_jcl_set_state(self, st);
}
BIND(void, jcl_thread_init_0, "java.lang.Thread_init$0", (void *self)) { jcl_thread_construct(self, 0, NULL); }
BIND(void, jcl_thread_init_1, "java.lang.Thread_init$1", (void *self, void *a)) { void *args[1] = {a}; jcl_thread_construct(self, 1, args); }
BIND(void, jcl_thread_init_2, "java.lang.Thread_init$2", (void *self, void *a, void *b)) { void *args[2] = {a, b}; jcl_thread_construct(self, 2, args); }
BIND(void, jcl_thread_init_3, "java.lang.Thread_init$3", (void *self, void *a, void *b, void *c)) { void *args[3] = {a, b, c}; jcl_thread_construct(self, 3, args); }
BIND(void, jcl_thread_init_4, "java.lang.Thread_init$4", (void *self, void *a, void *b, void *c, int64_t d)) { void *args[3] = {a, b, c}; (void)d; jcl_thread_construct(self, 4, args); }
BIND(void, jcl_thread_init_5, "java.lang.Thread_init$5", (void *self, void *a, void *b, void *c, int64_t d, int e)) { void *args[3] = {a, b, c}; (void)d; (void)e; jcl_thread_construct(self, 5, args); }
/* ---------------------------------------------------------------
 * Threads: real native threads that run one at a time (nabu_gil_*), switching where a thread blocks.
 * --------------------------------------------------------------- */
extern int GC_pthread_create(pthread_t *thread, const pthread_attr_t *attr, void *(*start)(void *), void *arg);

static void (*jcl_thread_run_fn)(void *) = NULL;
void nabu_set_thread_run_fn(void *fn) { jcl_thread_run_fn = (void (*)(void *))fn; }

static int jcl_live_threads = 0;

static int64_t jcl_now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static void jcl_throw_new(const char *cls, const char *super, const char *msg) {
    nabu_throw(jcl_throw(cls, super, msg));
}

typedef struct jcl_waiter { void *obj; int notified; struct jcl_waiter *next; } jcl_waiter;
static jcl_waiter *jcl_waiters = NULL;

static void jcl_notify(void *obj, int all) {
    for (jcl_waiter *w = jcl_waiters; w != NULL; w = w->next) {
        if (w->obj == obj && !w->notified) {
            w->notified = 1;
            if (!all) break;
        }
    }
    nabu_gil_signal();
}

static int jcl_owns_monitor(void *obj) {
    nabu_object *h = (nabu_object *)obj;
    return h->monitor_count > 0 && h->thread_id == nabu_current_thread_id();
}

/* Throws InterruptedException (and clears the flag) when the running thread was interrupted. */
static void jcl_check_interrupt(s_thread *cur) {
    if (cur->interrupted) {
        cur->interrupted = 0;
        jcl_throw_new("java/lang/InterruptedException", "java/lang/Exception", NULL);
    }
}

BIND(void, jcl_Object_notify, "java.lang.Object_notify", (void *self)) {
    if (!jcl_owns_monitor(self)) jcl_throw_new("java/lang/IllegalMonitorStateException", "java/lang/RuntimeException", "current thread is not owner");
    jcl_notify(self, 0);
}
BIND(void, jcl_java_lang_Object_notifyAll, "java.lang.Object_notifyAll", (void *self)) {
    if (!jcl_owns_monitor(self)) jcl_throw_new("java/lang/IllegalMonitorStateException", "java/lang/RuntimeException", "current thread is not owner");
    jcl_notify(self, 1);
}

BIND(void, jcl_Object_wait0, "java.lang.Object_wait0", (void *self, int64_t timeout)) {
    nabu_object *h = (nabu_object *)self;
    if (!jcl_owns_monitor(self)) jcl_throw_new("java/lang/IllegalMonitorStateException", "java/lang/RuntimeException", "current thread is not owner");
    s_thread *cur = jcl_thread_of(jcl_current_thread());
    jcl_check_interrupt(cur);
    if (!nabu_threads_active) {
        /* Nobody can notify: return as a spurious wakeup, after the time. */
        if (timeout > 0) jcl_sleep_ms(timeout);
        return;
    }
    const int me = nabu_current_thread_id();
    const int32_t depth = h->monitor_count;
    jcl_waiter w = { self, 0, NULL };
    jcl_waiter **tail = &jcl_waiters;
    while (*tail != NULL) tail = &(*tail)->next;
    *tail = &w;
    h->monitor_count = 0;
    h->thread_id = 0;
    nabu_gil_signal();
    const int64_t start = jcl_now_ms();
    while (!w.notified && !cur->interrupted) {
        if (timeout > 0) {
            int64_t left = timeout - (jcl_now_ms() - start);
            if (left <= 0) break;
            nabu_gil_wait(left);
        } else {
            nabu_gil_wait(-1);
        }
    }
    for (jcl_waiter **p = &jcl_waiters; *p != NULL; p = &(*p)->next) {
        if (*p == &w) { *p = w.next; break; }
    }
    while (h->monitor_count > 0 && h->thread_id != me) nabu_gil_wait(-1);
    h->monitor_count = depth;
    h->thread_id = me;
    if (!w.notified) jcl_check_interrupt(cur);
}

BIND(void, jcl_ThreadSupport_waitOn0, "io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime.ThreadSupport_waitOn0", (void *object, int64_t millis)) {
    jcl_Object_wait0(object, millis);
}

BIND(int, jcl_ThreadSupport_stateOf0, "io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime.ThreadSupport_stateOf0", (void *thread)) {
    return jcl_thread_of(thread)->state;
}

BIND(void, jcl_java_lang_Thread_sleep, "java.lang.Thread_sleep", (int64_t ms)) {
    s_thread *cur = jcl_thread_of(jcl_current_thread());
    jcl_check_interrupt(cur);
    if (ms <= 0) return;
    if (!nabu_threads_active) { jcl_sleep_ms(ms); return; }
    const int64_t start = jcl_now_ms();
    while (!cur->interrupted) {
        int64_t left = ms - (jcl_now_ms() - start);
        if (left <= 0) break;
        nabu_gil_wait(left);
    }
    jcl_check_interrupt(cur);
}

BIND(void, jcl_Unsafe_park, "jdk.internal.misc.Unsafe_park", (void *self, int absolute, int64_t time)) {
    (void)self;
    s_thread *cur = jcl_thread_of(jcl_current_thread());
    if (cur->permit) { cur->permit = 0; return; }
    if (cur->interrupted) return;
    int64_t ms = -1;
    if (absolute) {
        struct timespec now;
        clock_gettime(CLOCK_REALTIME, &now);
        ms = time - ((int64_t)now.tv_sec * 1000 + now.tv_nsec / 1000000);
        if (ms <= 0) return;
    } else if (time > 0) {
        ms = (time + 999999) / 1000000;
    } else if (time < 0) {
        return;
    }
    if (!nabu_threads_active) { if (ms > 0) jcl_sleep_ms(ms); return; }
    const int64_t start = jcl_now_ms();
    while (!cur->permit && !cur->interrupted) {
        if (ms >= 0) {
            int64_t left = ms - (jcl_now_ms() - start);
            if (left <= 0) break;
            nabu_gil_wait(left);
        } else {
            nabu_gil_wait(-1);
        }
    }
    cur->permit = 0;
}
BIND(void, jcl_Unsafe_unpark, "jdk.internal.misc.Unsafe_unpark", (void *self, void *thread)) {
    (void)self;
    s_thread *st = thread == NULL ? NULL : (s_thread *)jcl_state_kind(thread, K_THREAD);
    if (st == NULL) return;
    st->permit = 1;
    nabu_gil_signal();
}

BIND(void, jcl_Thread_yield, "java.lang.Thread_yield", (void)) { nabu_gil_yield(); }
BIND(void, jcl_Thread_onSpinWait, "java.lang.Thread_onSpinWait", (void)) { nabu_gil_yield(); }

BIND(int, jcl_Thread_interrupted, "java.lang.Thread_interrupted", (void)) {
    s_thread *cur = jcl_thread_of(jcl_current_thread());
    int was = cur->interrupted;
    cur->interrupted = 0;
    return was;
}
BIND(int, jcl_Thread_isInterrupted, "java.lang.Thread_isInterrupted", (void *self)) { return jcl_thread_of(self)->interrupted; }
BIND(void, jcl_Thread_clearInterrupt, "java.lang.Thread_clearInterrupt", (void *self)) { jcl_thread_of(self)->interrupted = 0; }
BIND(void, jcl_Thread_interrupt, "java.lang.Thread_interrupt", (void *self)) {
    jcl_thread_of(self)->interrupted = 1;
    nabu_gil_signal();
}
BIND(int, jcl_java_lang_Thread_isAlive, "java.lang.Thread_isAlive", (void *self)) { return jcl_thread_of(self)->state == 1; }

/* The body of a native thread: take the turn, run Thread.run through the program's ThreadSupport, tell the joiners. */
static void *jcl_thread_main(void *arg) {
    s_thread *st = (s_thread *)arg;
    nabu_gil_acquire();
    jcl_cur_thread = st->self;
    nabu_set_current_thread_id((int)st->id);
    if (jcl_thread_run_fn != NULL) jcl_thread_run_fn(st->self);
    st->state = 2;
    if (!st->daemon) jcl_live_threads--;
    jcl_notify(st->self, 1);
    nabu_gil_release();
    return NULL;
}

/* At exit the JVM waits for the threads that are not daemons, unless System.exit was called. */
static void jcl_await_threads(void) {
    if (jcl_exiting) return;
    while (jcl_live_threads > 0) nabu_gil_wait(-1);
}

BIND(void, jcl_java_lang_Thread_start, "java.lang.Thread_start", (void *self)) {
    s_thread *st = (s_thread *)jcl_state_kind(self, K_THREAD);
    if (st == NULL || st->state != 0) jcl_throw_new("java/lang/IllegalThreadStateException", "java/lang/IllegalArgumentException", NULL);
    if (!nabu_threads_active) {
        nabu_threads_active = 1;
        nabu_gil_acquire();
        atexit(jcl_await_threads);
    }
    st->state = 1;
    st->self = self;
    if (!st->daemon) jcl_live_threads++;
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
    pthread_attr_setstacksize(&attr, (size_t)16 << 20);
    pthread_t handle;
    int rc = GC_pthread_create(&handle, &attr, jcl_thread_main, st);
    pthread_attr_destroy(&attr);
    if (rc != 0) {
        st->state = 2;
        if (!st->daemon) jcl_live_threads--;
        jcl_throw_new("java/lang/IllegalThreadStateException", "java/lang/IllegalArgumentException", "unable to create native thread");
    }
}

BIND(void *, jcl_Thread_threadLocals, "java.lang.Thread_threadLocals", (void *self)) { return jcl_thread_of(self)->threadLocals; }
BIND(void, jcl_Thread_setThreadLocals, "java.lang.Thread_setThreadLocals", (void *self, void *map)) { jcl_thread_of(self)->threadLocals = map; }
BIND(void *, jcl_Thread_inheritableThreadLocals, "java.lang.Thread_inheritableThreadLocals", (void *self)) { return jcl_thread_of(self)->inheritableThreadLocals; }
BIND(void, jcl_Thread_setInheritableThreadLocals, "java.lang.Thread_setInheritableThreadLocals", (void *self, void *map)) { jcl_thread_of(self)->inheritableThreadLocals = map; }
BIND(void *, jcl_Thread_terminatingThreadLocals, "java.lang.Thread_terminatingThreadLocals", (void *self)) { (void)self; return NULL; }
BIND(void, jcl_Thread_setTerminatingThreadLocals, "java.lang.Thread_setTerminatingThreadLocals", (void *self, void *map)) { (void)self; (void)map; }
BIND(void *, jcl_Thread_currentCarrierThread, "java.lang.Thread_currentCarrierThread", (void)) { return jcl_current_thread(); }
BIND(void *, jcl_Thread_getThreadGroup, "java.lang.Thread_getThreadGroup", (void *self)) { (void)self; return NULL; }
BIND(void, jcl_Thread_blockedOn, "java.lang.Thread_blockedOn", (void *self, void *interruptible)) { (void)self; (void)interruptible; }
BIND(void, jcl_Thread_setDaemon, "java.lang.Thread_setDaemon", (void *self, int on)) { jcl_thread_of(self)->daemon = on; }
BIND(int, jcl_Thread_isDaemon, "java.lang.Thread_isDaemon", (void *self)) { return jcl_thread_of(self)->daemon; }
BIND(int, jcl_Thread_getPriority, "java.lang.Thread_getPriority", (void *self)) { return jcl_thread_of(self)->priority; }
BIND(int64_t, jcl_Thread_getId, "java.lang.Thread_getId", (void *self)) { return jcl_thread_of(self)->id; }
BIND(void, jcl_Thread_setPriority, "java.lang.Thread_setPriority", (void *self, int p)) { jcl_thread_of(self)->priority = p; }
BIND(void, jcl_Thread_setName, "java.lang.Thread_setName", (void *self, void *name)) { jcl_thread_of(self)->name = jcl_dup(nabu_cstr(name)); }
BIND(void, jcl_Thread_setContextClassLoader, "java.lang.Thread_setContextClassLoader", (void *self, void *loader)) { (void)self; (void)loader; }
BIND(void, jcl_Thread_setUncaughtExceptionHandler, "java.lang.Thread_setUncaughtExceptionHandler", (void *self, void *handler)) { (void)self; (void)handler; }
BIND(void *, jcl_Thread_currentThread, "java.lang.Thread_currentThread", (void)) { return jcl_current_thread(); }
BIND(void *, jcl_VirtualThread_currentThread, "java.lang.VirtualThread_currentThread", (void)) { return jcl_current_thread(); }
BIND(int, jcl_Thread_isVirtual, "java.lang.Thread_isVirtual", (void *self)) { (void)self; return 0; }
BIND(int64_t, jcl_Thread_threadId, "java.lang.Thread_threadId", (void *self)) { return jcl_thread_of(self)->id; }
BIND(void *, jcl_Thread_getName, "java.lang.Thread_getName", (void *self)) { return nabu_jstring_new(jcl_thread_of(self)->name); }
BIND(int, jcl_Thread_holdsLock, "java.lang.Thread_holdsLock", (void *obj)) { (void)obj; return 1; }
BIND(void *, jcl_Thread_getContextClassLoader, "java.lang.Thread_getContextClassLoader", (void *self)) { (void)self; return NULL; }
BIND(void *, jcl_Thread_getUncaughtExceptionHandler, "java.lang.Thread_getUncaughtExceptionHandler", (void *self)) { (void)self; return NULL; }
BIND(void *, jcl_Thread_getDefaultUncaughtExceptionHandler, "java.lang.Thread_getDefaultUncaughtExceptionHandler", (void)) { return NULL; }

static void *jcl_the_runtime(void) {
    static void *runtime = NULL;
    if (runtime == NULL) runtime = jcl_new_str_obj("runtime");
    return runtime;
}
BIND(void *, jcl_Runtime_getRuntime, "java.lang.Runtime_getRuntime", (void)) { return jcl_the_runtime(); }
BIND(int64_t, jcl_Runtime_maxMemory, "java.lang.Runtime_maxMemory", (void *self)) { (void)self; return INT64_MAX; }
BIND(int64_t, jcl_Runtime_freeMemory, "java.lang.Runtime_freeMemory", (void *self)) { (void)self; return (int64_t)64 * 1024 * 1024; }
BIND(int64_t, jcl_Runtime_totalMemory, "java.lang.Runtime_totalMemory", (void *self)) { (void)self; return (int64_t)256 * 1024 * 1024; }
BIND(void, jcl_System_gc, "java.lang.System_gc", (void)) { }
BIND(void *, jcl_System_lineSeparator, "java.lang.System_lineSeparator", (void)) {
#ifdef _WIN32
    return nabu_jstring_new("\r\n");
#else
    return nabu_jstring_new("\n");
#endif
}

BIND(void *, jcl_Reflection_getCallerClass, "jdk.internal.reflect.Reflection_getCallerClass", (void)) { return NULL; }
BIND(void *, jcl_NPE_getExtendedNPEMessage, "java.lang.NullPointerException_getExtendedNPEMessage", (void *self)) { (void)self; return NULL; }

BIND(int, jcl_Class_equals, "java.lang.Class_equals", (void *self, void *other)) {
    if (self == other) return 1;
    if (self == NULL || other == NULL) return 0;
    return strcmp(jcl_str(self), jcl_str(other)) == 0;
}
BIND(int32_t, jcl_Class_hashCode, "java.lang.Class_hashCode", (void *self)) {
    const unsigned char *s = (const unsigned char *)jcl_str(self);
    uint32_t h = 5381;
    while (*s) h = h * 33u + *s++;
    return (int32_t)h;
}
BIND(int, jcl_Class_isInstance, "java.lang.Class_isInstance", (void *self, void *obj)) {
    if (obj == NULL) return 0;
    const char *name = jcl_str(self);
    if (nabu_is_jstring(obj)) {
        return strcmp(name, "java/lang/String") == 0 || strcmp(name, "java/lang/Object") == 0
                || strcmp(name, "java/lang/CharSequence") == 0;
    }
    if (!nabu_jcl_is_object(obj) && jcl_compiled_type_name(obj) == NULL) return 0;
    return nabu_instanceof(obj, name);
}
BIND(int32_t, jcl_Class_getModifiers, "java.lang.Class_getModifiers", (void *self)) { (void)self; return 1; }
BIND(int, jcl_Class_isAnnotation, "java.lang.Class_isAnnotation", (void *self)) { (void)self; return 0; }
BIND(int, jcl_Class_isAnonymousClass, "java.lang.Class_isAnonymousClass", (void *self)) { (void)self; return 0; }
BIND(int, jcl_Class_isLocalClass, "java.lang.Class_isLocalClass", (void *self)) { (void)self; return 0; }
BIND(int, jcl_Class_isRecord, "java.lang.Class_isRecord", (void *self)) { (void)self; return 0; }
BIND(void *, jcl_Class_getClassLoader0, "java.lang.Class_getClassLoader0", (void *self)) { (void)self; return NULL; }
BIND(void *, jcl_Class_getTypeName, "java.lang.Class_getTypeName", (void *self)) { return nabu_jstring_new(jcl_source_name(jcl_str(self), 0)); }

/* VM.getNanoTimeAdjustment(offsetInSeconds): nanoseconds of the current time since that offset, or -1 when
 * the difference does not fit (more than about 136 years). */
BIND(int64_t, jcl_VM_getNanoTimeAdjustment, "jdk.internal.misc.VM_getNanoTimeAdjustment", (int64_t offsetSeconds)) {
    int64_t seconds, nanos;
#ifdef _WIN32
    FILETIME ft;
    GetSystemTimePreciseAsFileTime(&ft);
    int64_t ticks = (((int64_t)ft.dwHighDateTime << 32) | ft.dwLowDateTime) - 116444736000000000LL;
    seconds = ticks / 10000000LL;
    nanos = (ticks % 10000000LL) * 100;
#else
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    seconds = ts.tv_sec;
    nanos = ts.tv_nsec;
#endif
    int64_t diff = seconds - offsetSeconds;
    if (diff > 4294967295LL || diff < -4294967295LL) return -1;
    return diff * 1000000000LL + nanos;
}

/* TimeZone.getSystemTimeZoneID: the TZ variable, else the Windows zone mapped to its tz database id, else UTC. */
BIND(void *, jcl_TimeZone_getSystemTimeZoneID, "java.util.TimeZone_getSystemTimeZoneID", (void *javaHome)) {
    (void)javaHome;
    const char *tz = getenv("TZ");
    if (tz != NULL && tz[0] != 0) return nabu_jstring_new(tz);
#ifdef _WIN32
    static const struct { const char *windows; const char *id; } zones[] = {
        {"UTC", "UTC"}, {"GMT Standard Time", "Europe/London"}, {"W. Europe Standard Time", "Europe/Berlin"},
        {"Central Europe Standard Time", "Europe/Budapest"}, {"Romance Standard Time", "Europe/Paris"},
        {"Central European Standard Time", "Europe/Warsaw"}, {"E. Europe Standard Time", "Europe/Chisinau"},
        {"GTB Standard Time", "Europe/Bucharest"}, {"FLE Standard Time", "Europe/Kiev"},
        {"Russian Standard Time", "Europe/Moscow"}, {"Eastern Standard Time", "America/New_York"},
        {"Central Standard Time", "America/Chicago"}, {"Mountain Standard Time", "America/Denver"},
        {"Pacific Standard Time", "America/Los_Angeles"}, {"Alaskan Standard Time", "America/Anchorage"},
        {"Hawaiian Standard Time", "Pacific/Honolulu"}, {"Canada Central Standard Time", "America/Regina"},
        {"Atlantic Standard Time", "America/Halifax"}, {"E. South America Standard Time", "America/Sao_Paulo"},
        {"Argentina Standard Time", "America/Buenos_Aires"}, {"India Standard Time", "Asia/Kolkata"},
        {"China Standard Time", "Asia/Shanghai"}, {"Tokyo Standard Time", "Asia/Tokyo"},
        {"Korea Standard Time", "Asia/Seoul"}, {"Singapore Standard Time", "Asia/Singapore"},
        {"AUS Eastern Standard Time", "Australia/Sydney"}, {"New Zealand Standard Time", "Pacific/Auckland"},
        {"South Africa Standard Time", "Africa/Johannesburg"}, {"Egypt Standard Time", "Africa/Cairo"},
        {"Arab Standard Time", "Asia/Riyadh"}, {"Turkey Standard Time", "Europe/Istanbul"},
    };
    DYNAMIC_TIME_ZONE_INFORMATION info;
    memset(&info, 0, sizeof info);
    if (GetDynamicTimeZoneInformation(&info) != TIME_ZONE_ID_INVALID) {
        char key[256];
        WideCharToMultiByte(CP_UTF8, 0, info.TimeZoneKeyName, -1, key, sizeof key, NULL, NULL);
        for (size_t i = 0; i < sizeof zones / sizeof zones[0]; i++) {
            if (strcmp(zones[i].windows, key) == 0) return nabu_jstring_new(zones[i].id);
        }
    }
#endif
    return nabu_jstring_new("UTC");
}

/* java.lang.ref.Reference: a reference is as strong as any other pointer here (the collector does not
 * clear weak references), so a referent is only cleared by clear(). */
static void **jcl_referent_slot(void *ref) {
    intptr_t off = ref != NULL ? nabu_instance_field_offset(ref, "referent") : -1;
    return off >= 0 ? (void **)((char *)ref + off) : NULL;
}
BIND(int, jcl_Reference_refersTo0, "java.lang.ref.Reference_refersTo0", (void *self, void *obj)) {
    void **slot = jcl_referent_slot(self);
    return slot != NULL && *slot == obj;
}
BIND(int, jcl_PhantomReference_refersTo0, "java.lang.ref.PhantomReference_refersTo0", (void *self, void *obj)) {
    void **slot = jcl_referent_slot(self);
    return slot != NULL && *slot == obj;
}
BIND(void, jcl_Reference_clear0, "java.lang.ref.Reference_clear0", (void *self)) {
    void **slot = jcl_referent_slot(self);
    if (slot != NULL) *slot = NULL;
}
BIND(int, jcl_Reference_hasReferencePendingList, "java.lang.ref.Reference_hasReferencePendingList", (void)) { return 0; }
BIND(void *, jcl_Reference_getAndClearReferencePendingList, "java.lang.ref.Reference_getAndClearReferencePendingList", (void)) { return NULL; }

/* RandomSupport.fill0(byte[]): random bytes from the operating system. */
BIND(void, jcl_RandomSupport_fill0, "io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime.RandomSupport_fill0", (void *bytes)) {
    if (bytes == NULL) return;
    int32_t n = *(int32_t *)bytes;
    unsigned char *data = (unsigned char *)bytes + 8;
#ifdef _WIN32
    typedef BOOLEAN (WINAPI *rtl_gen_random)(PVOID, ULONG);
    static rtl_gen_random generate = NULL;
    if (generate == NULL) {
        HMODULE lib = LoadLibraryA("advapi32.dll");
        if (lib != NULL) generate = (rtl_gen_random)(void *)GetProcAddress(lib, "SystemFunction036");
    }
    if (generate != NULL && generate(data, (ULONG)n)) return;
#else
    FILE *f = fopen("/dev/urandom", "rb");
    if (f != NULL) {
        size_t got = fread(data, 1, (size_t)n, f);
        fclose(f);
        if (got == (size_t)n) return;
    }
#endif
    for (int32_t i = 0; i < n; i++) data[i] = (unsigned char)(rand() & 0xff);
}

/* java.util.zip.CRC32 natives (the JDK uses zlib). */
static uint32_t jcl_crc_table[256];
static int jcl_crc_ready = 0;
static uint32_t jcl_crc_update(uint32_t crc, const unsigned char *data, int32_t len) {
    if (!jcl_crc_ready) {
        for (uint32_t n = 0; n < 256; n++) {
            uint32_t c = n;
            for (int k = 0; k < 8; k++) c = (c & 1) ? 0xEDB88320u ^ (c >> 1) : c >> 1;
            jcl_crc_table[n] = c;
        }
        jcl_crc_ready = 1;
    }
    crc = ~crc;
    for (int32_t i = 0; i < len; i++) crc = jcl_crc_table[(crc ^ data[i]) & 0xff] ^ (crc >> 8);
    return ~crc;
}
BIND(int32_t, jcl_CRC32_update, "java.util.zip.CRC32_update$I32I32", (int32_t crc, int32_t b)) {
    unsigned char byte = (unsigned char)b;
    return (int32_t)jcl_crc_update((uint32_t)crc, &byte, 1);
}
BIND(int32_t, jcl_CRC32_updateBytes0, "java.util.zip.CRC32_updateBytes0", (int32_t crc, void *bytes, int32_t off, int32_t len)) {
    return (int32_t)jcl_crc_update((uint32_t)crc, (const unsigned char *)bytes + 8 + off, len);
}
BIND(int32_t, jcl_CRC32_updateByteBuffer0, "java.util.zip.CRC32_updateByteBuffer0", (int32_t crc, int64_t address, int32_t off, int32_t len)) {
    return (int32_t)jcl_crc_update((uint32_t)crc, (const unsigned char *)(intptr_t)address + off, len);
}

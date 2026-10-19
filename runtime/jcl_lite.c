#include "nabu_runtime.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ctype.h>
#include <math.h>
#include <time.h>
#include <errno.h>
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
    K_STR = 1, K_BOX, K_MAP, K_FIELD, K_ANN, K_THROW, K_CLASSOBJ, K_CTOR, K_METHOD
};

enum { B_I32, B_I64, B_BOOL, B_F64, B_F32, B_I16, B_I8, B_CHAR };

typedef struct { int kind; char *v; } s_str;
typedef struct { int kind; int t; union { int32_t i; int64_t j; double d; float f; int16_t c; } v; } s_box;
typedef struct { int kind; void **ks, **vs; int32_t size, cap; } s_map;
typedef struct { int kind; const nabu_field_info *f; char *cls; } s_field;
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

static int32_t box_as_i32(const void *p) { return (int32_t)box_as_i64(p); }

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
 * index into nabu_array_type_names, which the backend emits with the JVM
 * name of every array type it allocates ("[Ljava/lang/String;", "[I");
 * 0 means unknown. */
extern const char *const nabu_array_type_names[];
extern const int32_t nabu_array_type_count;

static const char *jcl_arr_type_name(const void *a) {
    int32_t id = a != NULL ? (int32_t)(((const uint32_t *)a)[1] >> 8) : 0;
    return id > 0 && id < nabu_array_type_count ? nabu_array_type_names[id] : NULL;
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
static int32_t jcl_arr_type_id(const char *name) {
    for (int32_t i = 1; i < nabu_array_type_count; i++) {
        if (nabu_array_type_names[i] != NULL && strcmp(nabu_array_type_names[i], name) == 0) return i;
    }
    return 0;
}
static void jcl_arr_set_type(void *a, const char *name) {
    int32_t es = ((int32_t *)a)[1] & 0xff;
    ((int32_t *)a)[1] = es | (jcl_arr_type_id(name) << 8);
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
    void *arr = jcl_arr_ref(n);
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

static void *jcl_empty_strs(void) { return jcl_arr_ref(0); }

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
    void *arr = jcl_arr_ref(n);
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
    void *arr = jcl_arr_ref(n);
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
BIND(void, jcl_java_lang_System_exit, "java.lang.System_exit", (int32_t code)) { fflush(NULL); exit(code); }
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
                nabu_throw(jcl_throw("java/io/IOException", "java/lang/Exception", "Write error"));
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

/* The absolute, normalized path; links and the case of names are not
 * resolved (the JDK does that on Windows). */
BIND(void *, jcl_fs_canonicalize0, JCL_FS("canonicalize0"), (void *self, void *pathString)) {
    const char *path = nabu_cstr(pathString);
#ifdef _WIN32
    wchar_t buf[32768];
    DWORD n = GetFullPathNameW(jcl_wide(path), 32768, buf, NULL);
    return n > 0 && n < 32768 ? jcl_jstring_wide(buf) : pathString;
#else
    char buf[PATH_MAX];
    return realpath(path, buf) != NULL ? nabu_jstring_new(buf) : pathString;
#endif
}

BIND(int32_t, jcl_fs_getNameMax0, JCL_FS("getNameMax0"), (void *self, void *pathString)) { return 255; }

BIND(int64_t, jcl_fs_getSpace0, JCL_FS("getSpace0"), (void *self, void *f, int32_t t)) {
#ifdef _WIN32
    ULARGE_INTEGER freeToCaller, total, freeTotal;
    if (!GetDiskFreeSpaceExW(jcl_wide(jcl_file_path_of(f)), &freeToCaller, &total, &freeTotal)) return 0;
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
    if (slot != NULL) *slot = fd;
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


BIND(int, jcl_java_lang_Thread_isAlive, "java.lang.Thread_isAlive", (void *self)) { (void)self; return 0; }
BIND(void, jcl_java_lang_Thread_sleep, "java.lang.Thread_sleep", (int64_t ms)) {
    if (ms > 0) {
#ifdef _WIN32
        Sleep((DWORD)ms);
#else
        struct timespec ts = { (time_t)(ms / 1000), (long)((ms % 1000) * 1000000L) };
        nanosleep(&ts, NULL);
#endif
    }
}
BIND(void, jcl_java_lang_Thread_start, "java.lang.Thread_start", (void *self)) { (void)self; }


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
    if (ri == NULL || ri->field_count <= 0) return jcl_arr_ref(0);
    void *arr = jcl_arr_ref(ri->field_count);
    for (int i = 0; i < ri->field_count; i++) jcl_arr_set(arr, i, jcl_field_obj(ri->type_name, &ri->fields[i]));
    return arr;
}
BIND(void *, jcl_java_lang_Class_getDeclaredMethod, "java.lang.Class_getDeclaredMethod", (void *self, void *nm, void *pts)) { (void)self; (void)nm; (void)pts; return NULL; }
BIND(void *, jcl_java_lang_Class_getDeclaredMethods, "java.lang.Class_getDeclaredMethods", (void *self)) { (void)self; return jcl_arr_ref(0); }
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
BIND(void *, jcl_java_lang_Class_getField, "java.lang.Class_getField", (void *self, void *nm)) {
    const nabu_reflection_info *ri = nabu_lookup_reflection(jcl_str(self));
    if (ri == NULL) return NULL;
    for (int i = 0; i < ri->field_count; i++) {
        if (strcmp(ri->fields[i].name, jcl_str(nm)) == 0) return jcl_field_obj(ri->type_name, &ri->fields[i]);
    }
    return NULL;
}
BIND(void *, jcl_java_lang_Class_getMethod, "java.lang.Class_getMethod", (void *self, void *nm, void *pts)) { (void)self; (void)nm; (void)pts; return NULL; }
BIND(void *, jcl_java_lang_Class_getMethods, "java.lang.Class_getMethods", (void *self)) { (void)self; return jcl_arr_ref(0); }
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

BIND(void *, jcl_java_lang_reflect_AccessibleObject_getAnnotation, "java.lang.reflect.AccessibleObject_getAnnotation", (void *self, void *ann)) {
    s_field *f = jcl_field_of(self);
    if (f != NULL) {
        const nabu_annotation_entry *e = nabu_get_field_annotation(f->cls, f->f->name, jcl_str(ann));
        return e != NULL ? jcl_ann_obj(e) : NULL;
    }
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
    if (jcl_field_of(self) != NULL) return jcl_java_lang_reflect_AccessibleObject_getAnnotation(self, ann);
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
BIND(int32_t, jcl_java_lang_reflect_Constructor_getModifiers, "java.lang.reflect.Constructor_getModifiers", (void *self)) { (void)self; return 1; }
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
BIND(void *, jcl_java_lang_reflect_Field_getGenericType, "java.lang.reflect.Field_getGenericType", (void *self)) {
    s_field *f = jcl_field_of(self);
    return jcl_class(f != NULL ? jcl_desc_name(f->f->type_desc) : "");
}
BIND(void *, jcl_java_lang_reflect_Field_getType, "java.lang.reflect.Field_getType", (void *self)) {
    s_field *f = jcl_field_of(self);
    return jcl_class(f != NULL ? jcl_desc_name(f->f->type_desc) : "");
}
BIND(int32_t, jcl_java_lang_reflect_Field_getModifiers, "java.lang.reflect.Field_getModifiers", (void *self)) { (void)self; return 0; }
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
BIND(void *, jcl_java_lang_reflect_Field_get, "java.lang.reflect.Field_get", (void *self, void *obj)) {
    s_field *f = jcl_field_of(self);
    if (f == NULL || obj == NULL) return NULL;
    const char *d = f->f->type_desc;
    const char *cls = f->cls;
    const char *nm = f->f->name;
    if (d == NULL || (d[0] != 'L' && d[0] != '[')) {
        switch (d != NULL ? d[0] : 'L') {
            case 'I': return box_i32(nabu_reflect_get_i32(obj, cls, nm));
            case 'J': return box_i64(nabu_reflect_get_i64(obj, cls, nm));
            case 'Z': return box_bool(nabu_reflect_get_i32(obj, cls, nm));
            case 'D': return box_f64(nabu_reflect_get_f64(obj, cls, nm));
            case 'F': return jcl_box(B_F32, 0, nabu_reflect_get_f32(obj, cls, nm));
            case 'S': case 'B': case 'C': return box_i16((int32_t)nabu_reflect_get_i32(obj, cls, nm));
            default: break;
        }
    }
    return nabu_reflect_get_ref(obj, cls, nm);
}
BIND(void, jcl_java_lang_reflect_Field_set, "java.lang.reflect.Field_set", (void *self, void *obj, void *val)) {
    s_field *f = jcl_field_of(self);
    if (f == NULL || obj == NULL) return;
    const char *d = f->f->type_desc;
    const char *cls = f->cls;
    const char *nm = f->f->name;
    if (d != NULL && d[0] != 'L' && d[0] != '[') {
        switch (d[0]) {
            case 'I': nabu_reflect_set_i32(obj, cls, nm, box_as_i32(val)); return;
            case 'J': nabu_reflect_set_i64(obj, cls, nm, box_as_i64(val)); return;
            case 'Z': nabu_reflect_set_i32(obj, cls, nm, box_as_i64(val) != 0); return;
            case 'D': nabu_reflect_set_f64(obj, cls, nm, box_as_f64(val)); return;
            case 'F': nabu_reflect_set_f32(obj, cls, nm, (float)box_as_f64(val)); return;
            case 'S': case 'B': case 'C': nabu_reflect_set_i32(obj, cls, nm, box_as_i32(val)); return;
            default: break;
        }
    }
    nabu_reflect_set_ref(obj, cls, nm, val);
}
/* java.lang.reflect.Method: a declaring class, a name and a JVM descriptor
 * (K_METHOD). Only the methods of dynamic proxies have one so far: the
 * generated proxy classes ask ProxySupport.method for theirs, which caches
 * them (one object per method, so == and equals agree). Method.invoke on
 * them is redirected to ProxySupport.invoke, which dispatches in bytecode. */
typedef struct { int kind; char *cls; char *name; char *desc; } s_method;

static s_method *jcl_method_of(void *self) { return (s_method *)jcl_state_kind(self, K_METHOD); }

/* ProxySupport.method0(Class declaring, String name, String descriptor). */
BIND(void *, jcl_proxy_method0, "io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime.ProxySupport_method0",
     (void *cls, void *name, void *desc)) {
    s_method *st = (s_method *)jcl_alloc(sizeof(s_method));
    st->kind = K_METHOD;
    st->cls = jcl_dup(jcl_str(cls));
    st->name = jcl_dup(jcl_str(name));
    st->desc = jcl_dup(jcl_str(desc));
    return jcl_new_obj("java/lang/reflect/Method", st);
}

/* ProxySupport.methodKey(Method): "owner#name#descriptor", or null for a
 * Method without K_METHOD state. */
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

static void * jcl_java_lang_reflect_Method_getName (void *self) {
    s_method *m = jcl_method_of(self);
    return (void *)(m != NULL ? m->name : "");
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
    return jcl_java_lang_reflect_Method_getReturnType(self);
}
BIND(void *, jcl_java_lang_reflect_Method_getParameterTypes, "java.lang.reflect.Method_getParameterTypes", (void *self)) {
    s_method *m = jcl_method_of(self);
    if (m == NULL || m->desc[0] != '(') return jcl_arr_ref(0);
    int32_t count = 0;
    for (const char *d = m->desc + 1; *d != ')' && *d != 0; d += jcl_desc_len(d)) count++;
    void *arr = jcl_arr_ref(count);
    jcl_arr_set_type(arr, "[Ljava/lang/Class;");
    int32_t i = 0;
    for (const char *d = m->desc + 1; *d != ')' && *d != 0; d += jcl_desc_len(d)) {
        jcl_arr_set(arr, i++, jcl_desc_class(d, jcl_desc_len(d)));
    }
    return arr;
}
BIND(int32_t, jcl_java_lang_reflect_Method_getParameterCount, "java.lang.reflect.Method_getParameterCount", (void *self)) {
    s_method *m = jcl_method_of(self);
    if (m == NULL || m->desc[0] != '(') return 0;
    int32_t count = 0;
    for (const char *d = m->desc + 1; *d != ')' && *d != 0; d += jcl_desc_len(d)) count++;
    return count;
}
BIND(void *, jcl_java_lang_reflect_Method_getGenericParameterTypes, "java.lang.reflect.Method_getGenericParameterTypes", (void *self)) {
    return jcl_java_lang_reflect_Method_getParameterTypes(self);
}
BIND(void *, jcl_java_lang_reflect_Method_getParameterAnnotations, "java.lang.reflect.Method_getParameterAnnotations", (void *self)) { (void)self; return jcl_arr_ref(0); }
/* public, and abstract for an interface method (Object's are not). */
BIND(int32_t, jcl_java_lang_reflect_Method_getModifiers, "java.lang.reflect.Method_getModifiers", (void *self)) {
    s_method *m = jcl_method_of(self);
    return m != NULL && strcmp(m->cls, "java/lang/Object") != 0 ? 1 | 1024 : 1;
}
BIND(void *, jcl_java_lang_reflect_Method_invoke, "java.lang.reflect.Method_invoke", (void *self, void *tgt, void *args)) { (void)self; (void)tgt; (void)args; return NULL; }
BIND(void, jcl_java_lang_reflect_Method_setAccessible, "java.lang.reflect.Method_setAccessible", (void *self, int v)) { (void)self; (void)v; }
/* As the JDK: "public abstract java.lang.String a.I.m(int,java.lang.Object)". */
static void * jcl_java_lang_reflect_Method_toString (void *self) {
    s_method *m = jcl_method_of(self);
    if (m == NULL) return (void *)"";
    const char *r = strchr(m->desc, ')');
    char *cls = jcl_dup(m->cls);
    for (char *c = cls; *c; c++) if (*c == '/') *c = '.';
    char *out = jcl_concat(jcl_java_lang_reflect_Method_getModifiers(self) == 1 ? "public " : "public abstract ",
            r != NULL ? jcl_desc_type_name(r + 1, strlen(r + 1)) : "void");
    out = jcl_concat(jcl_concat(jcl_concat(jcl_concat(out, " "), cls), "."), m->name);
    out = jcl_concat(out, "(");
    int first = 1;
    for (const char *d = m->desc + 1; *d != ')' && *d != 0; d += jcl_desc_len(d)) {
        if (!first) out = jcl_concat(out, ",");
        out = jcl_concat(out, jcl_desc_type_name(d, jcl_desc_len(d)));
        first = 0;
    }
    return (void *)jcl_concat(out, ")");
}
BIND(void *, jcl_java_lang_reflect_Method_toString_jstr, "java.lang.reflect.Method_toString", (void *self)) {
    void *r = (void *)jcl_java_lang_reflect_Method_toString(self);
    return r == NULL ? NULL : nabu_jstring_new(nabu_cstr(r));
}
static void * jcl_java_lang_reflect_Method_toGenericString (void *self) { (void)self; return (void *)""; }
BIND(void *, jcl_java_lang_reflect_Method_toGenericString_jstr, "java.lang.reflect.Method_toGenericString", (void *self)) {
    void *r = (void *)jcl_java_lang_reflect_Method_toGenericString(self);
    return r == NULL ? NULL : nabu_jstring_new(nabu_cstr(r));
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

BIND(void *, jcl_java_lang_reflect_ParameterizedType_getActualTypeArguments, "java.lang.reflect.ParameterizedType_getActualTypeArguments", (void *self)) { (void)self; return jcl_arr_ref(0); }
BIND(void *, jcl_java_lang_reflect_ParameterizedType_getRawType, "java.lang.reflect.ParameterizedType_getRawType", (void *self)) { (void)self; return jcl_class(""); }
BIND(void *, jcl_java_lang_reflect_Proxy_getInvocationHandler, "java.lang.reflect.Proxy_getInvocationHandler", (void *self, void *p)) { (void)self; (void)p; return NULL; }
BIND(int, jcl_java_lang_reflect_Proxy_isProxyClass, "java.lang.reflect.Proxy_isProxyClass", (void *self)) { (void)self; return 0; }
BIND(void *, jcl_java_lang_reflect_Proxy_newProxyInstance, "java.lang.reflect.Proxy_newProxyInstance", (void *cl, void *ifs, void *h)) { (void)cl; (void)ifs; (void)h; return NULL; }
BIND(void *, jcl_java_lang_reflect_WildcardType_getLowerBounds, "java.lang.reflect.WildcardType_getLowerBounds", (void *self)) { (void)self; return jcl_arr_ref(0); }
BIND(void *, jcl_java_lang_reflect_WildcardType_getUpperBounds, "java.lang.reflect.WildcardType_getUpperBounds", (void *self)) { (void)self; return jcl_arr_ref(0); }
BIND(void *, jcl_java_lang_reflect_GenericArrayType_getGenericComponentType, "java.lang.reflect.GenericArrayType_getGenericComponentType", (void *self)) { (void)self; return jcl_class(""); }

BIND(void *, jcl_java_lang_reflect_Method_getAnnotation, "java.lang.reflect.Method_getAnnotation", (void *self, void *ann)) { (void)self; (void)ann; return NULL; }




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
BIND(void, jcl_java_lang_Object_notifyAll, "java.lang.Object_notifyAll", (void *self)) { (void)self; }
BIND(void *, jcl_java_lang_String_intern, "java.lang.String_intern", (void *self)) { return self; }
BIND(int32_t, jcl_java_lang_Runtime_availableProcessors, "java.lang.Runtime_availableProcessors", (void *self)) { (void)self; return 1; }
BIND(void, jcl_java_lang_Runtime_gc, "java.lang.Runtime_gc", (void *self)) { (void)self; }
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
/* Throwable.fillInStackTrace(int): er is geen stack-walker, dus de
 * exception krijgt geen stack trace maar blijft verder bruikbaar. */
BIND(void *, jcl_java_lang_Throwable_fillInStackTrace0, "java.lang.Throwable_fillInStackTrace$Pf850ce3bI32", (void *self, int32_t dummy)) { (void)dummy; return self; }

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
BIND(void, jcl_Unsafe_fullFence, "jdk.internal.misc.Unsafe_fullFence", (void *self)) { (void)self; }
BIND(void, jcl_Unsafe_ensureClassInitialized0, "jdk.internal.misc.Unsafe_ensureClassInitialized0", (void *self, void *cls)) { (void)self; (void)cls; }
BIND(int, jcl_Unsafe_shouldBeInitialized0, "jdk.internal.misc.Unsafe_shouldBeInitialized0", (void *self, void *cls)) { (void)self; (void)cls; return 0; }
BIND(void, jcl_Unsafe_park, "jdk.internal.misc.Unsafe_park", (void *self, int absolute, int64_t time)) { (void)self; (void)absolute; (void)time; }
BIND(void, jcl_Unsafe_unpark, "jdk.internal.misc.Unsafe_unpark", (void *self, void *thread)) { (void)self; (void)thread; }


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

static void *jcl_main_thread(void) {
    static void *thread = NULL;
    if (thread == NULL) thread = jcl_new_str_obj("main");
    return thread;
}
BIND(void *, jcl_Thread_currentThread, "java.lang.Thread_currentThread", (void)) { return jcl_main_thread(); }
BIND(void *, jcl_VirtualThread_currentThread, "java.lang.VirtualThread_currentThread", (void)) { return jcl_main_thread(); }
BIND(int, jcl_Thread_isVirtual, "java.lang.Thread_isVirtual", (void *self)) { (void)self; return 0; }
BIND(int64_t, jcl_Thread_threadId, "java.lang.Thread_threadId", (void *self)) { (void)self; return 1; }
BIND(void *, jcl_Thread_getName, "java.lang.Thread_getName", (void *self)) { (void)self; return nabu_jstring_new("main"); }
BIND(int, jcl_Thread_interrupted, "java.lang.Thread_interrupted", (void)) { return 0; }
BIND(int, jcl_Thread_isInterrupted, "java.lang.Thread_isInterrupted", (void *self)) { (void)self; return 0; }
BIND(void, jcl_Thread_clearInterrupt, "java.lang.Thread_clearInterrupt", (void *self)) { (void)self; }
BIND(void, jcl_Thread_interrupt, "java.lang.Thread_interrupt", (void *self)) { (void)self; }
BIND(void, jcl_Thread_yield, "java.lang.Thread_yield", (void)) { }
BIND(void, jcl_Thread_onSpinWait, "java.lang.Thread_onSpinWait", (void)) { }
BIND(int, jcl_Thread_holdsLock, "java.lang.Thread_holdsLock", (void *obj)) { (void)obj; return 1; }
BIND(void *, jcl_Thread_getContextClassLoader, "java.lang.Thread_getContextClassLoader", (void *self)) { (void)self; return NULL; }
BIND(void *, jcl_Thread_getUncaughtExceptionHandler, "java.lang.Thread_getUncaughtExceptionHandler", (void *self)) { (void)self; return NULL; }
BIND(void *, jcl_Thread_getDefaultUncaughtExceptionHandler, "java.lang.Thread_getDefaultUncaughtExceptionHandler", (void)) { return NULL; }
BIND(void, jcl_Object_wait0, "java.lang.Object_wait0", (void *self, int64_t timeout)) { (void)self; (void)timeout; }

static void *jcl_the_runtime(void) {
    static void *runtime = NULL;
    if (runtime == NULL) runtime = jcl_new_str_obj("runtime");
    return runtime;
}
BIND(void *, jcl_Runtime_getRuntime, "java.lang.Runtime_getRuntime", (void)) { return jcl_the_runtime(); }
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

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
    K_STR = 1, K_SB, K_BOX, K_LIST, K_SET, K_MAP, K_VIEW, K_ITER,
    K_ENTRY, K_FIELD, K_ANN, K_THROW, K_READER, K_FILE, K_STREAM, K_SW,
    K_CLASSOBJ, K_ENUM, K_CTOR, K_OTHER
};

enum { B_I32, B_I64, B_BOOL, B_F64, B_F32, B_I16, B_I8, B_CHAR };

typedef struct { int kind; char *v; } s_str;
typedef struct { int kind; int t; union { int32_t i; int64_t j; double d; float f; int16_t c; } v; } s_box;
typedef struct { int kind; char *buf; int32_t len, cap; } s_sb;
typedef struct { int kind; void **it; int32_t size, cap; } s_list;
typedef struct { int kind; void *src; void **items; int32_t size, idx; } s_iter;
typedef struct { int kind; void *k, *v; } s_ent;
typedef struct { int kind; void **ks, **vs; int32_t size, cap; } s_map;
typedef struct { int kind; void **it; int32_t size, cap; } s_set;
typedef struct { int kind; const nabu_field_info *f; char *cls; } s_field;
typedef struct { int kind; const nabu_annotation_entry *e; } s_ann;
typedef struct { int kind; char *msg; char *cls; void *cause; } s_throw;
typedef struct { int kind; FILE *f; char *buf; int32_t len, pos; int eof; } s_reader;
typedef struct { int kind; char *path; } s_file;
typedef struct { int kind; FILE *f; } s_stream;
typedef struct { int kind; char *buf; int32_t len, cap; } s_sw;
typedef struct { int kind; const char *nm; int32_t ord; } s_enum;
typedef struct { int kind; int32_t v; } s_atomic;

static void *jcl_alloc(size_t n) {
    void *p = JCL_MEM(n);
    if (!p) { fprintf(stderr, "jcl: allocatie faalde (%zu)\n", n); abort(); }
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
        const void *box = ba != NULL ? a : b;
        const void *oth = ba != NULL ? b : a;
        const char *s = jcl_str(oth);
        if (ba->t == B_BOOL) return (strcmp(s, "true") == 0) == (ba->v.i != 0);
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

static char *jcl_itoa(int64_t v) {
    char buf[32];
    snprintf(buf, sizeof(buf), "%lld", (long long)v);
    return jcl_dup(buf);
}

static char *jcl_dtoa(double v) {
    char buf[64];
    snprintf(buf, sizeof(buf), v == (double)(int64_t)v ? "%.1f" : "%.17g", v);
    return jcl_dup(buf);
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

static char *jcl_any(const void *p) {
    if (p == NULL) return (char *)"null";
    if (nabu_jcl_is_object(p)) {
        void *st = nabu_jcl_state((void *)p);
        if (st == NULL && nabu_compiled_type_name(p) != NULL) {
            /* Gecompileerd object (bv. een wrapper uit jcl_box): zijn eigen
             * toString(). */
            return (char *)nabu_cstr(nabu_object_to_string((void *)p));
        }
        if (st == NULL) {
            nabu_object *h = (nabu_object *)p;
            nabu_type_info *ti = (nabu_type_info *)h->type;
            return jcl_dup(ti != NULL && ti->name != NULL ? ti->name : "object");
        }
        switch (*(int *)st) {
            case K_STR: return ((s_str *)st)->v != NULL ? ((s_str *)st)->v : (char *)"";
            case K_SB: { s_sb *sb = (s_sb *)st; char *o = (char *)jcl_alloc((size_t)sb->len + 1); memcpy(o, sb->buf, (size_t)sb->len); o[sb->len] = 0; return o; }
            case K_BOX: {
                s_box *b = (s_box *)st;
                switch (b->t) {
                    case B_BOOL: return jcl_dup(b->v.i ? "true" : "false");
                    case B_F64: return jcl_dtoa(b->v.d);
                    case B_F32: return jcl_dtoa(b->v.f);
                    case B_I16: case B_CHAR: { char *s = (char *)jcl_alloc(8); s[0] = (char)(b->v.c & 0xff); s[1] = 0; return s; }
                    case B_I64: return jcl_itoa(b->v.j);
                    default: return jcl_itoa(b->v.i);
                }
            }
            case K_THROW: { s_throw *t = (s_throw *)st; size_t n = strlen(t->cls) + strlen(t->msg) + 4; char *o = (char *)jcl_alloc(n); snprintf(o, n, "%s: %s", t->cls, t->msg); return o; }
            default: return jcl_dup("");
        }
    }
    return (char *)p;
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

static void *jcl_class_cstr(const char *dotted_or_slash) { return jcl_class(dotted_or_slash); }

#define JCL_ENUM_CACHE_MAX 64
static void *jcl_enum_cache[JCL_ENUM_CACHE_MAX];
static int jcl_enum_n = 0;

static void *jcl_enum(const char *type_iname, const char *nm) {
    for (int i = 0; i < jcl_enum_n; i++) {
        s_enum *e = (s_enum *)nabu_jcl_state(jcl_enum_cache[i]);
        if (e != NULL && strcmp(e->nm, nm) == 0) {
            nabu_object *h = (nabu_object *)jcl_enum_cache[i];
            nabu_type_info *ti = (nabu_type_info *)h->type;
            if (ti != NULL && ti->name != NULL && strcmp(ti->name, type_iname) == 0) return jcl_enum_cache[i];
        }
    }
    s_enum *st = (s_enum *)jcl_alloc(sizeof(s_enum));
    st->kind = K_ENUM;
    st->nm = jcl_dup(nm);
    st->ord = 0;
    void *obj = jcl_alloc(48 + 8);
    nabu_init_object_ex(obj, type_iname, "java/lang/Enum");
    nabu_jcl_set_state(obj, st);
    if (jcl_enum_n < JCL_ENUM_CACHE_MAX) jcl_enum_cache[jcl_enum_n++] = obj;
    return obj;
}

static void *jcl_scope_local(void) { return jcl_enum("picocli/CommandLine$ScopeType", "LOCAL"); }

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

static void *jcl_arr_char(int32_t n) {
    if (n < 0) n = 0;
    void *p = jcl_alloc(8 + (size_t)n + 1);
    ((int32_t *)p)[0] = n;
    ((int32_t *)p)[1] = 1;
    return p;
}

static int32_t jcl_arr_len(void *a) { return a != NULL ? *(int32_t *)a : 0; }

/* Elementgrootte van een array; 8 (referentie) voor een array zonder geldige
 * grootte in de header, zoals vóór de elementgrootte bestond. */
static size_t jcl_arr_esize(const void *a) {
    int32_t es = a != NULL ? ((const int32_t *)a)[1] : 0;
    return (es == 1 || es == 2 || es == 4 || es == 8) ? (size_t)es : 8;
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

/* Een String[]-annotatiewaarde: de elementen zijn Java-strings. */
static void *ann_strs(const void *ann, const char *name) {
    const nabu_annotation_value *v = ann_v(ann, name);
    if (v == NULL || v->text == NULL || v->text[0] == 0) return jcl_empty_strs();
    void *arr = jcl_strs(v->text, 1);
    int32_t n = jcl_arr_len(arr);
    void **items = (void **)((char *)arr + 8);
    for (int32_t i = 0; i < n; i++) items[i] = nabu_jstring_new(nabu_cstr(items[i]));
    return arr;
}

static void *ann_classes(const void *ann, const char *name) {
    const nabu_annotation_value *v = ann_v(ann, name);
    if (v == NULL || v->text == NULL || v->text[0] == 0) return jcl_arr_ref(0);
    return jcl_strs(v->text, 1);
}

/* Een Class[]-annotatiewaarde: elk element een Class-object. */
static void *ann_class_array(const void *ann, const char *name) {
    void *names = ann_classes(ann, name);
    int32_t n = jcl_arr_len(names);
    void *arr = jcl_arr_ref(n);
    void **src = (void **)((char *)names + 8);
    void **dst = (void **)((char *)arr + 8);
    for (int32_t i = 0; i < n; i++) dst[i] = jcl_class(nabu_cstr(src[i]));
    return arr;
}

static void jcl_nyi(const char *sym) {
    fprintf(stderr, "[nabu-jcl] niet-geimplementeerd: %s\n", sym);
    fflush(stderr);
    abort();
}

static void *jcl_throw(const char *cls, const char *super, const char *msg) {
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

static char *jcl_sub(const char *s, int32_t from, int32_t to) {
    size_t n = strlen(s);
    if (from < 0) from = 0;
    if ((size_t)to > n) to = (int32_t)n;
    if (from > to) from = to;
    char *o = (char *)jcl_alloc((size_t)(to - from) + 1);
    memcpy(o, s + from, (size_t)(to - from));
    o[to - from] = 0;
    return o;
}

#define ANN_STR(cname, sym, key, dflt) BIND(void *, cname, sym, (void *a)) { const char *t = ann_text(a, key, dflt); return t == NULL ? NULL : nabu_jstring_new(t); }
#define ANN_ARR(cname, sym, key) BIND(void *, cname, sym, (void *a)) { return ann_strs(a, key); }
#define ANN_BOOL(cname, sym, key, dflt) BIND(int, cname, sym, (void *a)) { return ann_bool(a, key, dflt); }
#define ANN_INT(cname, sym, key, dflt) BIND(int32_t, cname, sym, (void *a)) { return (int32_t)ann_num(a, key, dflt); }
#define ANN_CH(cname, sym, key, dflt) BIND(int16_t, cname, sym, (void *a)) { return (int16_t)ann_num(a, key, dflt); }
/* Class-waardige attributen: een (geïnterneerd) Class-object, geen String.
 * picocli vergelijkt de waarde met een class-literal (isNonDefault) en maakt
 * anders een instantie via reflectie; met een String-waarde was elke default
 * "niet-default" en werd bv. de preprocessor null. */
#define ANN_CLS(cname, sym, key, dflt) BIND(void *, cname, sym, (void *a)) { const char *t = ann_text(a, key, dflt); return jcl_class((t == NULL || *t == 0) ? dflt : t); }
#define ANN_CLSARR(cname, sym, key) BIND(void *, cname, sym, (void *a)) { return ann_class_array(a, key); }
#define ANN_ENUM(cname, sym, key, type, dflt_nm) BIND(void *, cname, sym, (void *a)) { const char *t = ann_text(a, key, NULL); return jcl_enum(type, (t != NULL && *t != 0) ? t : dflt_nm); }

ANN_BOOL(jcl_Command_abbreviateSynopsis, "Command_abbreviateSynopsis", "abbreviateSynopsis", 0)
ANN_BOOL(jcl_Command_addMethodSubcommands, "Command_addMethodSubcommands", "addMethodSubcommands", 1)
ANN_BOOL(jcl_Command_mixinStandardHelpOptions, "Command_mixinStandardHelpOptions", "mixinStandardHelpOptions", 0)
ANN_ARR(jcl_Command_aliases, "Command_aliases", "aliases")
ANN_STR(jcl_Command_commandListHeading, "Command_commandListHeading", "commandListHeading", "Commands:%n")
ANN_ARR(jcl_Command_customSynopsis, "Command_customSynopsis", "customSynopsis")
ANN_CLS(jcl_Command_defaultValueProvider, "Command_defaultValueProvider", "defaultValueProvider", "picocli/CommandLine$NoDefaultProvider")
ANN_ARR(jcl_Command_description, "Command_description", "description")
ANN_STR(jcl_Command_descriptionHeading, "Command_descriptionHeading", "descriptionHeading", "")
ANN_ARR(jcl_Command_exitCodeList, "Command_exitCodeList", "exitCodeList")
ANN_STR(jcl_Command_exitCodeListHeading, "Command_exitCodeListHeading", "exitCodeListHeading", "")
ANN_INT(jcl_Command_exitCodeOnExecutionException, "Command_exitCodeOnExecutionException", "exitCodeOnExecutionException", 1)
ANN_INT(jcl_Command_exitCodeOnInvalidInput, "Command_exitCodeOnInvalidInput", "exitCodeOnInvalidInput", 2)
ANN_INT(jcl_Command_exitCodeOnSuccess, "Command_exitCodeOnSuccess", "exitCodeOnSuccess", 0)
ANN_INT(jcl_Command_exitCodeOnUsageHelp, "Command_exitCodeOnUsageHelp", "exitCodeOnUsageHelp", 0)
ANN_INT(jcl_Command_exitCodeOnVersionHelp, "Command_exitCodeOnVersionHelp", "exitCodeOnVersionHelp", 0)
ANN_ARR(jcl_Command_footer, "Command_footer", "footer")
ANN_STR(jcl_Command_footerHeading, "Command_footerHeading", "footerHeading", "")
ANN_ARR(jcl_Command_header, "Command_header", "header")
ANN_STR(jcl_Command_headerHeading, "Command_headerHeading", "headerHeading", "")
ANN_BOOL(jcl_Command_helpCommand, "Command_helpCommand", "helpCommand", 0)
ANN_BOOL(jcl_Command_hidden, "Command_hidden", "hidden", 0)
ANN_CLS(jcl_Command_modelTransformer, "Command_modelTransformer", "modelTransformer", "picocli/CommandLine$NoOpModelTransformer")
ANN_STR(jcl_Command_name, "Command_name", "name", "<main class>")
ANN_STR(jcl_Command_optionListHeading, "Command_optionListHeading", "optionListHeading", "")
ANN_STR(jcl_Command_parameterListHeading, "Command_parameterListHeading", "parameterListHeading", "")
ANN_CLS(jcl_Command_preprocessor, "Command_preprocessor", "preprocessor", "picocli/CommandLine$NoOpParameterPreprocessor")
ANN_CH(jcl_Command_requiredOptionMarker, "Command_requiredOptionMarker", "requiredOptionMarker", 32)
ANN_STR(jcl_Command_resourceBundle, "Command_resourceBundle", "resourceBundle", "")
BIND(void *, jcl_Command_scope, "Command_scope", (void *a)) { const char *t = ann_text(a, "scope", NULL); return jcl_enum("picocli/CommandLine$ScopeType", (t != NULL && *t != 0) ? t : "LOCAL"); }
ANN_STR(jcl_Command_separator, "Command_separator", "separator", "=")
ANN_BOOL(jcl_Command_showAtFileInUsageHelp, "Command_showAtFileInUsageHelp", "showAtFileInUsageHelp", 0)
ANN_BOOL(jcl_Command_showDefaultValues, "Command_showDefaultValues", "showDefaultValues", 0)
ANN_BOOL(jcl_Command_showEndOfOptionsDelimiterInUsageHelp, "Command_showEndOfOptionsDelimiterInUsageHelp", "showEndOfOptionsDelimiterInUsageHelp", 0)
ANN_BOOL(jcl_Command_sortOptions, "Command_sortOptions", "sortOptions", 1)
ANN_BOOL(jcl_Command_sortSynopsis, "Command_sortSynopsis", "sortSynopsis", 1)
ANN_CLSARR(jcl_Command_subcommands, "Command_subcommands", "subcommands")
ANN_BOOL(jcl_Command_subcommandsRepeatable, "Command_subcommandsRepeatable", "subcommandsRepeatable", 0)
ANN_STR(jcl_Command_synopsisHeading, "Command_synopsisHeading", "synopsisHeading", "Usage: ")
ANN_STR(jcl_Command_synopsisSubcommandLabel, "Command_synopsisSubcommandLabel", "synopsisSubcommandLabel", "[COMMAND]")
ANN_BOOL(jcl_Command_usageHelpAutoWidth, "Command_usageHelpAutoWidth", "usageHelpAutoWidth", 0)
ANN_INT(jcl_Command_usageHelpWidth, "Command_usageHelpWidth", "usageHelpWidth", 80)
ANN_ARR(jcl_Command_version, "Command_version", "version")
ANN_CLS(jcl_Command_versionProvider, "Command_versionProvider", "versionProvider", "picocli/CommandLine$NoVersionProvider")

BIND(int, jcl_ArgGroup_exclusive, "ArgGroup_exclusive", (void *a)) { return ann_bool(a, "exclusive", 1); }
ANN_STR(jcl_ArgGroup_heading, "ArgGroup_heading", "heading", "__no_heading__")
ANN_STR(jcl_ArgGroup_headingKey, "ArgGroup_headingKey", "headingKey", "__no_heading_key__")
ANN_STR(jcl_ArgGroup_multiplicity, "ArgGroup_multiplicity", "multiplicity", "0..1")
ANN_INT(jcl_ArgGroup_order, "ArgGroup_order", "order", -1)
BIND(int, jcl_ArgGroup_validate, "ArgGroup_validate", (void *a)) { return ann_bool(a, "validate", 1); }

BIND(void *, jcl_Mixin_name, "Mixin_name", (void *a)) { return nabu_jstring_new(ann_text(a, "name", "")); }
ANN_ENUM(jcl_Spec_value, "Spec_value", "value", "picocli/CommandLine$Spec$Target", "SELF")

ANN_STR(jcl_Option_arity, "Option_arity", "arity", "")
ANN_CLS(jcl_Option_completionCandidates, "Option_completionCandidates", "completionCandidates", "picocli/CommandLine$NoCompletionCandidates")
ANN_CLSARR(jcl_Option_converter, "Option_converter", "converter")
ANN_STR(jcl_Option_defaultValue, "Option_defaultValue", "defaultValue", "__no_default_value__")
ANN_ARR(jcl_Option_description, "Option_description", "description")
ANN_STR(jcl_Option_descriptionKey, "Option_descriptionKey", "descriptionKey", "")
ANN_BOOL(jcl_Option_echo, "Option_echo", "echo", 0)
ANN_STR(jcl_Option_fallbackValue, "Option_fallbackValue", "fallbackValue", "")
ANN_BOOL(jcl_Option_help, "Option_help", "help", 0)
ANN_BOOL(jcl_Option_hidden, "Option_hidden", "hidden", 0)
ANN_BOOL(jcl_Option_hideParamSyntax, "Option_hideParamSyntax", "hideParamSyntax", 0)
ANN_BOOL(jcl_Option_interactive, "Option_interactive", "interactive", 0)
ANN_STR(jcl_Option_mapFallbackValue, "Option_mapFallbackValue", "mapFallbackValue", "__unspecified__")
ANN_ARR(jcl_Option_names, "Option_names", "names")
ANN_BOOL(jcl_Option_negatable, "Option_negatable", "negatable", 0)
ANN_INT(jcl_Option_order, "Option_order", "order", -1)
ANN_CLS(jcl_Option_parameterConsumer, "Option_parameterConsumer", "parameterConsumer", "picocli/CommandLine$NullParameterConsumer")
ANN_STR(jcl_Option_paramLabel, "Option_paramLabel", "paramLabel", "")
ANN_CLS(jcl_Option_preprocessor, "Option_preprocessor", "preprocessor", "picocli/CommandLine$NoOpParameterPreprocessor")
ANN_STR(jcl_Option_prompt, "Option_prompt", "prompt", "")
ANN_BOOL(jcl_Option_required, "Option_required", "required", 0)
BIND(void *, jcl_Option_scope, "Option_scope", (void *a)) { const char *t = ann_text(a, "scope", NULL); return jcl_enum("picocli/CommandLine$ScopeType", (t != NULL && *t != 0) ? t : "LOCAL"); }
ANN_ENUM(jcl_Option_showDefaultValue, "Option_showDefaultValue", "showDefaultValue", "picocli/CommandLine$Help$Visibility", "ON_DEMAND")
ANN_STR(jcl_Option_split, "Option_split", "split", "")
ANN_STR(jcl_Option_splitSynopsisLabel, "Option_splitSynopsisLabel", "splitSynopsisLabel", "")
ANN_CLSARR(jcl_Option_type, "Option_type", "type")
ANN_BOOL(jcl_Option_usageHelp, "Option_usageHelp", "usageHelp", 0)
ANN_BOOL(jcl_Option_versionHelp, "Option_versionHelp", "versionHelp", 0)

ANN_STR(jcl_Parameters_arity, "Parameters_arity", "arity", "")
ANN_CLS(jcl_Parameters_completionCandidates, "Parameters_completionCandidates", "completionCandidates", "picocli/CommandLine$NoCompletionCandidates")
ANN_CLSARR(jcl_Parameters_converter, "Parameters_converter", "converter")
ANN_STR(jcl_Parameters_defaultValue, "Parameters_defaultValue", "defaultValue", "__no_default_value__")
ANN_ARR(jcl_Parameters_description, "Parameters_description", "description")
ANN_STR(jcl_Parameters_descriptionKey, "Parameters_descriptionKey", "descriptionKey", "")
ANN_BOOL(jcl_Parameters_echo, "Parameters_echo", "echo", 0)
ANN_BOOL(jcl_Parameters_hidden, "Parameters_hidden", "hidden", 0)
ANN_BOOL(jcl_Parameters_hideParamSyntax, "Parameters_hideParamSyntax", "hideParamSyntax", 0)
ANN_STR(jcl_Parameters_index, "Parameters_index", "index", "")
ANN_BOOL(jcl_Parameters_interactive, "Parameters_interactive", "interactive", 0)
ANN_STR(jcl_Parameters_mapFallbackValue, "Parameters_mapFallbackValue", "mapFallbackValue", "__unspecified__")
ANN_CLS(jcl_Parameters_parameterConsumer, "Parameters_parameterConsumer", "parameterConsumer", "picocli/CommandLine$NullParameterConsumer")
ANN_STR(jcl_Parameters_paramLabel, "Parameters_paramLabel", "paramLabel", "")
ANN_CLS(jcl_Parameters_preprocessor, "Parameters_preprocessor", "preprocessor", "picocli/CommandLine$NoOpParameterPreprocessor")
ANN_STR(jcl_Parameters_prompt, "Parameters_prompt", "prompt", "")
BIND(void *, jcl_Parameters_scope, "Parameters_scope", (void *a)) { const char *t = ann_text(a, "scope", NULL); return jcl_enum("picocli/CommandLine$ScopeType", (t != NULL && *t != 0) ? t : "LOCAL"); }
ANN_ENUM(jcl_Parameters_showDefaultValue, "Parameters_showDefaultValue", "showDefaultValue", "picocli/CommandLine$Help$Visibility", "ON_DEMAND")
ANN_STR(jcl_Parameters_split, "Parameters_split", "split", "")
ANN_STR(jcl_Parameters_splitSynopsisLabel, "Parameters_splitSynopsisLabel", "splitSynopsisLabel", "")
ANN_CLSARR(jcl_Parameters_type, "Parameters_type", "type")

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
    if (!nabu_jcl_is_object(self)) return jcl_hash(self);
    s_str *st = (s_str *)jcl_state_kind(self, K_STR);
    if (st != NULL && st->v != NULL) return jcl_hash(st->v);
    return (int32_t)((intptr_t)self >> 4);
}

BIND(int32_t, jcl_java_lang_String_indexOf_i32, "java.lang.String_indexOf$PI32", (void *self, int32_t ch)) {
    const char *s = jcl_str(self);
    const char *r = strchr(s, (char)(ch & 0xff));
    return r != NULL ? (int32_t)(r - s) : -1;
}
BIND(int32_t, jcl_java_lang_String_indexOf_from, "java.lang.String_indexOf$PP523beb0aI32", (void *self, void *p, int32_t from)) {
    const char *s = jcl_str(self);
    size_t n = strlen(s);
    if (from < 0) from = 0;
    if ((size_t)from > n) return -1;
    const char *r = strstr(s + from, jcl_str(p));
    return r != NULL ? (int32_t)(r - s) : -1;
}
BIND(void *, jcl_java_lang_String_substring_i32, "java.lang.String_substring$PI32", (void *self, int32_t from)) {
    const char *s = jcl_str(self);
    return (void *)jcl_sub(s, from, (int32_t)strlen(s));
}
static void * jcl_java_lang_String_toLowerCase (void *self, void *loc) {
    (void)loc;
    const char *s = jcl_str(self);
    size_t n = strlen(s);
    char *o = (char *)jcl_alloc(n + 1);
    for (size_t i = 0; i < n; i++) o[i] = (char)tolower((unsigned char)s[i]);
    return (void *)o;
}
BIND(void *, jcl_java_lang_String_toLowerCase_1, "java.lang.String_toLowerCase$P", (void *self)) { return jcl_java_lang_String_toLowerCase(self, NULL); }
static void * jcl_java_lang_String_toUpperCase (void *self, void *loc) {
    (void)loc;
    const char *s = jcl_str(self);
    size_t n = strlen(s);
    char *o = (char *)jcl_alloc(n + 1);
    for (size_t i = 0; i < n; i++) o[i] = (char)toupper((unsigned char)s[i]);
    return (void *)o;
}
BIND(void *, jcl_java_lang_String_toUpperCase_1, "java.lang.String_toUpperCase$P", (void *self)) { return jcl_java_lang_String_toUpperCase(self, NULL); }
static int jcl_glob_match(const char *pat, const char *s) {
    if (*pat == 0) return *s == 0;
    if (*pat == '*') {
        while (*pat == '*') pat++;
        if (*pat == 0) return 1;
        for (; *s; s++) if (jcl_glob_match(pat, s)) return 1;
        return 0;
    }
    if (*s == 0) return 0;
    if (*pat == '.' || *pat == *s) return jcl_glob_match(pat + 1, s + 1);
    return 0;
}
static void * jcl_java_lang_String_split (void *self, void *pat, int32_t limit) {
    const char *s = jcl_str(self), *p = jcl_str(pat);
    if (*p == 0) return jcl_arr_ref(0);
    int32_t cap = 8, n = 0;
    void **items = (void **)jcl_alloc(sizeof(void *) * (size_t)cap);
    const char *start = s;
    size_t np = strlen(p);
    for (const char *q = s; ; q++) {
        int hit = (*q == 0) || (np == 1 && *q == *p) || (np > 1 && strncmp(q, p, np) == 0);
        if (hit && *q != 0) {
            if (n == cap) { cap *= 2; items = (void **)JCL_REALLOC(items, sizeof(void *) * (size_t)cap); }
            size_t len = (size_t)(q - start);
            char *piece = (char *)jcl_alloc(len + 1);
            memcpy(piece, start, len);
            piece[len] = 0;
            items[n++] = piece;
            q += np - 1;
            start = q + 1;
            if (limit > 0 && n == limit - 1) { start = q + 1; break; }
        } else if (*q == 0) {
            break;
        }
    }
    if (limit == 0) {
        while (n > 0 && ((char *)items[n - 1])[0] == 0) n--;
    }
    if (n == 0 || *start != 0 || limit != 0) {
        if (n == cap) { cap += 2; items = (void **)JCL_REALLOC(items, sizeof(void *) * (size_t)cap); }
        items[n++] = jcl_dup(start);
    }
    if (limit > 0 && n > limit && limit > 0) {
        int32_t keep = limit;
        if (keep > 0 && n > keep) {
            char *joined = jcl_dup((char *)items[keep - 1]);
            for (int32_t i = keep; i < n; i++) {
                joined = jcl_concat(joined, p);
                joined = jcl_concat(joined, (char *)items[i]);
            }
            items[keep - 1] = joined;
            n = keep;
        }
    }
    void *out = jcl_arr_ref(n);
    for (int32_t i = 0; i < n; i++) jcl_arr_set(out, i, items[i]);
    return out;
}
BIND(void *, jcl_java_lang_String_split_2, "java.lang.String_split$PP523beb0a", (void *self, void *pat)) { return jcl_java_lang_String_split(self, pat, 0); }
static int jcl_ieq(const char *a, const char *b) { for (; *a && *b; a++, b++) if (tolower((unsigned char)*a) != tolower((unsigned char)*b)) return 0; return *a == *b; }

static char *jcl_fmt_apply(const char *fmt, void *args) {
    int32_t argc = jcl_arr_len(args);
    int ai = 0;
    size_t cap = strlen(fmt) + 64;
    char *o = (char *)jcl_alloc(cap);
    size_t out = 0;
    for (const char *p = fmt; *p; p++) {
        if (*p != '%') {
            while (out + 2 >= cap) { cap *= 2; o = (char *)JCL_REALLOC(o, cap); }
            o[out++] = *p;
            continue;
        }
        p++;
        if (*p == 0) break;
        if (*p == '%') { while (out + 2 >= cap) { cap *= 2; o = (char *)JCL_REALLOC(o, cap); } o[out++] = '%'; continue; }
        if (*p == 'n') { while (out + 2 >= cap) { cap *= 2; o = (char *)JCL_REALLOC(o, cap); } o[out++] = '\n'; continue; }
        char spec[32];
        int si = 0;
        spec[si++] = '%';
        while (*p && !strchr("sdfcbxegSD", *p) && si < 30) spec[si++] = *p++;
        if (*p == 0) { spec[si] = 0; break; }
        spec[si++] = *p;
        spec[si] = 0;
        void *arg = ai < argc ? jcl_arr_elem(args, ai) : NULL;
        ai++;
        char tmp[128];
        const char *conv = strrchr(spec, 's') ? "s" : strrchr(spec, 'd') ? "d" : strrchr(spec, 'f') ? "f"
                : strrchr(spec, 'c') ? "c" : strrchr(spec, 'b') ? "b" : strrchr(spec, 'x') ? "x" : "g";
        if (conv[0] == 's') snprintf(tmp, sizeof(tmp), spec, jcl_any(arg));
        else if (conv[0] == 'd') snprintf(tmp, sizeof(tmp), spec, (int)box_as_i64(arg));
        else if (conv[0] == 'x') snprintf(tmp, sizeof(tmp), spec, (unsigned)(int)box_as_i64(arg));
        else if (conv[0] == 'c') snprintf(tmp, sizeof(tmp), spec, (int)(box_as_i64(arg) & 0xff));
        else if (conv[0] == 'b') snprintf(tmp, sizeof(tmp), "%s", box_as_i64(arg) ? "true" : "false");
        else snprintf(tmp, sizeof(tmp), spec, box_as_f64(arg));
        size_t nt = strlen(tmp);
        while (out + nt + 2 >= cap) { cap = cap * 2 + nt; o = (char *)JCL_REALLOC(o, cap); }
        memcpy(o + out, tmp, nt);
        out += nt;
    }
    o[out] = 0;
    return o;
}


/* StringBuilder komt volledig uit JDK-bytecode. De vroegere C-varianten van
 * append/insert/<init> vervingen de gecompileerde bodies: hun tekst belandde
 * in een losse C-buffer (een gecompileerd object draagt geen jcl-state) en
 * toString() gaf "" (bv. LocaleData's "sun.text.resources" + ".FormatData"). */
BIND(void, jcl_java_lang_String_init, "java_lang_String_init", (void *self, void *src)) {
    if (self == NULL) return;
    s_str *st = (s_str *)jcl_alloc(sizeof(s_str));
    st->kind = K_STR;
    st->v = jcl_dup(jcl_str(src));
    nabu_jcl_set_state(self, st);
}

BIND(void *, jcl_java_lang_Boolean_valueOf_u, "java_lang_Boolean_valueOf", (int v)) { return box_bool(v); }
BIND(void *, jcl_java_lang_Byte_valueOf_u, "java_lang_Byte_valueOf", (int8_t v)) { return jcl_box(B_I8, v, 0); }
BIND(void *, jcl_java_lang_Short_valueOf_u, "java_lang_Short_valueOf", (int16_t v)) { return box_i16(v); }
BIND(void *, jcl_java_lang_Integer_valueOf_u, "java_lang_Integer_valueOf", (int32_t v)) { return box_i32(v); }
BIND(void *, jcl_java_lang_Long_valueOf_u, "java_lang_Long_valueOf", (int64_t v)) { return box_i64(v); }
BIND(void *, jcl_java_lang_Double_valueOf_f64, "java.lang.Double_valueOf$F64", (double v)) { return box_f64(v); }
BIND(void *, jcl_java_lang_Double_valueOf_u, "java_lang_Double_valueOf", (double v)) { return box_f64(v); }
BIND(void *, jcl_java_lang_Float_valueOf_u, "java_lang_Float_valueOf", (float v)) { return jcl_box(B_F32, 0, v); }
BIND(void *, jcl_java_lang_Character_valueOf_u, "java_lang_Character_valueOf", (int16_t c)) { return box_i16(c); }



BIND(void, jcl_java_lang_Throwable_printStackTrace_P, "java.lang.Throwable_printStackTrace$P", (void *self)) {
    fprintf(stderr, "Exception in thread \"main\" %s\n", jcl_any(self));
    fflush(stderr);
}
BIND(void, jcl_java_lang_Throwable_printStackTrace_ps, "java.lang.Throwable_printStackTrace$PP2f442d83", (void *self, void *ps)) {
    (void)ps;
    jcl_java_lang_Throwable_printStackTrace_P(self);
}

static void jcl_fill_throw(void *self, const char *cls, const char *super, const char *msg) {
    if (self == NULL) return;
    s_throw *st = (s_throw *)jcl_alloc(sizeof(s_throw));
    st->kind = K_THROW;
    st->msg = jcl_dup(msg);
    st->cls = jcl_dup(cls);
    st->cause = NULL;
    nabu_object *h = (nabu_object *)self;
    void *vt = h->vtable, *it = h->itable;
    nabu_init_object_ex(self, cls, super);
    h->vtable = vt;
    h->itable = it;
    nabu_jcl_set_state(self, st);
}


BIND(void, jcl_java_lang_System_arraycopy, "java.lang.System_arraycopy", (void *src, int32_t srcPos, void *dst, int32_t dstPos, int32_t len)) {
    if (src == NULL || dst == NULL) { jcl_throw("java/lang/NullPointerException", "java/lang/RuntimeException", "arraycopy"); return; }
    if (len <= 0) return;
    if (srcPos < 0 || dstPos < 0 || srcPos + len > jcl_arr_len(src) || dstPos + len > jcl_arr_len(dst)) {
        jcl_throw("java/lang/ArrayIndexOutOfBoundsException", "java/lang/IndexOutOfBoundsException", "arraycopy");
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
BIND(void, jcl_java_io_FileDescriptor_close0, "java.io.FileDescriptor_close0", (void *self)) { (void)self; }

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
    FILE *f = fd == 1 ? stdout : fd == 2 ? stderr : NULL;
    if (f == NULL) {
        nabu_throw(jcl_throw("java/io/IOException", "java/lang/Exception", "Stream Closed"));
        return;
    }
#ifdef _WIN32
    /* FileOutputStream schrijft ruwe bytes; de JDK heeft line.separator
     * ("
") al uitgeschreven. In tekstmodus maakte de CRT er "

" van. */
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

BIND(void, jcl_java_io_PrintWriter_init, "java_io_PrintWriter_init", (void *self, void *out, int autoFlush)) {
    (void)autoFlush;
    if (self == NULL) return;
    s_stream *s = (s_stream *)jcl_alloc(sizeof(s_stream));
    s->kind = K_STREAM;
    s->f = (out == NULL) ? stdout : NULL;
    nabu_jcl_set_state(self, s);
}
BIND(void, jcl_java_io_PrintWriter_init_PP42ca0260, "java_io_PrintWriter_init$PP42ca0260", (void *self, void *out)) {
    jcl_java_io_PrintWriter_init(self, out, 0);
}

BIND(void, jcl_java_io_File_init, "java_io_File_init", (void *self, void *path)) {
    if (self == NULL) return;
    s_file *s = (s_file *)jcl_alloc(sizeof(s_file));
    s->kind = K_FILE;
    s->path = jcl_dup(jcl_str(path));
    nabu_jcl_set_state(self, s);
}
static void jcl_file_init_child(void *self, const char *p, const char *c) {
    size_t n = strlen(p) + strlen(c) + 2;
    char *full = (char *)jcl_alloc(n);
    snprintf(full, n, "%s/%s", p, c);
    jcl_java_io_File_init(self, (void *)full);
}
/* File(String parent, String child). Stond eerder onder het symbool van
 * File(String, File): de frontend koos die overload ten onrechte, omdat de
 * constructorkeuze elk referentiepaar als passend zag. */
BIND(void, jcl_java_io_File_init_PP523beb0aP523beb0a, "java_io_File_init$PP523beb0aP523beb0a", (void *self, void *parent, void *child)) {
    if (self == NULL) return;
    jcl_file_init_child(self, jcl_str(parent), jcl_str(child));
}
static const char *jcl_file_path(void *self) {
    s_file *s = (s_file *)jcl_state_kind(self, K_FILE);
    if (s != NULL) return s->path;
    return jcl_str(self);
}
/* File(File parent, String child). */
BIND(void, jcl_java_io_File_init_PP3a58077P523beb0a, "java_io_File_init$PP3a58077P523beb0a", (void *self, void *parent, void *child)) {
    if (self == NULL) return;
    jcl_file_init_child(self, jcl_file_path(parent), jcl_str(child));
}
BIND(int, jcl_java_io_File_exists, "java.io.File_exists", (void *self)) {
    FILE *f = fopen(jcl_file_path(self), "rb");
    if (f != NULL) { fclose(f); return 1; }
    return 0;
}
BIND(int, jcl_java_io_File_canRead, "java.io.File_canRead", (void *self)) { return jcl_java_io_File_exists(self); }
static void * jcl_java_io_File_getAbsolutePath (void *self) {
    return (void *)jcl_dup(jcl_file_path(self));
}
BIND(void *, jcl_java_io_File_getAbsolutePath_jstr, "java.io.File_getAbsolutePath", (void *self)) {
    void *r = (void *)jcl_java_io_File_getAbsolutePath(self);
    return r == NULL ? NULL : nabu_jstring_new(nabu_cstr(r));
}

static void jcl_reader_from(void *self, void *inner) {
    s_reader *r = (s_reader *)jcl_alloc(sizeof(s_reader));
    r->kind = K_READER;
    r->buf = (char *)jcl_alloc(512);
    r->len = 0;
    r->pos = 0;
    r->eof = 0;
    if (inner != NULL && nabu_jcl_is_object(inner)) {
        s_reader *ri = (s_reader *)jcl_state_kind(inner, K_READER);
        if (ri != NULL) { r->f = ri->f; }
        else {
            s_file *sf = (s_file *)jcl_state_kind(inner, K_FILE);
            if (sf != NULL) r->f = fopen(sf->path, "rb");
        }
    }
    if (self != NULL) nabu_jcl_set_state(self, r);
}
static char *jcl_reader_line(s_reader *r) {
    if (r == NULL) return NULL;
    FILE *f = r->f != NULL ? r->f : stdin;
    if (r->eof) return NULL;
    size_t cap = 128, n = 0;
    char *line = (char *)jcl_alloc(cap);
    int any = 0;
    for (;;) {
        int c = fgetc(f);
        if (c == EOF) { r->eof = 1; break; }
        any = 1;
        if (c == '\n') break;
        if (c == '\r') continue;
        if (n + 2 >= cap) { cap *= 2; line = (char *)JCL_REALLOC(line, cap); }
        line[n++] = (char)c;
    }
    if (!any && n == 0) return NULL;
    line[n] = 0;
    return line;
}
static void * jcl_java_io_BufferedReader_readLine (void *self) {
    s_reader *r = (s_reader *)jcl_state_kind(self, K_READER);
    if (r == NULL) return NULL;
    return (void *)jcl_reader_line(r);
}
BIND(void *, jcl_java_io_BufferedReader_readLine_jstr, "java.io.BufferedReader_readLine", (void *self)) {
    void *r = (void *)jcl_java_io_BufferedReader_readLine(self);
    return r == NULL ? NULL : nabu_jstring_new(nabu_cstr(r));
}
BIND(void *, jcl_java_io_LineNumberReader_readLine, "java.io.LineNumberReader_readLine", (void *self)) {
    return jcl_java_io_BufferedReader_readLine(self);
}
BIND(void, jcl_java_io_BufferedReader_init, "java_io_BufferedReader_init", (void *self, void *in)) { jcl_reader_from(self, in); }
BIND(void, jcl_java_io_BufferedWriter_init, "java_io_BufferedWriter_init", (void *self, void *out)) { (void)out; jcl_java_io_PrintWriter_init(self, out, 0); }
BIND(void, jcl_java_io_InputStreamReader_init, "java_io_InputStreamReader_init", (void *self, void *in)) { jcl_reader_from(self, in); }
BIND(void, jcl_java_io_LineNumberReader_init, "java_io_LineNumberReader_init", (void *self, void *in)) { jcl_reader_from(self, in); }
BIND(void, jcl_java_io_FileReader_init, "java_io_FileReader_init", (void *self, void *path)) { jcl_java_io_File_init(self, path); jcl_reader_from(self, self); }
BIND(void, jcl_java_io_FileInputStream_init, "java_io_FileInputStream_init", (void *self, void *path)) { jcl_java_io_File_init(self, path); }
BIND(void, jcl_java_io_OutputStreamWriter_init, "java_io_OutputStreamWriter_init", (void *self, void *out, void *cs)) {
    (void)cs;
    jcl_java_io_PrintWriter_init(self, out, 0);
}
BIND(void, jcl_java_io_Closeable_close, "java.io.Closeable_close", (void *self)) {
    s_reader *r = (s_reader *)jcl_state_kind(self, K_READER);
    if (r != NULL && r->f != NULL) { fclose(r->f); r->f = NULL; }
}
BIND(void, jcl_java_io_StringWriter_init, "java_io_StringWriter_init", (void *self)) {
    if (self == NULL) return;
    s_sw *s = (s_sw *)jcl_alloc(sizeof(s_sw));
    s->kind = K_SW;
    s->cap = 64;
    s->buf = (char *)jcl_alloc(64);
    s->buf[0] = 0;
    nabu_jcl_set_state(self, s);
}
BIND(void, jcl_java_io_StreamTokenizer_init, "java_io_StreamTokenizer_init", (void *self, void *r)) { (void)self; (void)r; }
BIND(void, jcl_java_io_StreamTokenizer_commentChar, "java.io.StreamTokenizer_commentChar", (void *self, int32_t c)) { (void)self; (void)c; }
BIND(void, jcl_java_io_StreamTokenizer_quoteChar, "java.io.StreamTokenizer_quoteChar", (void *self, int32_t c)) { (void)self; (void)c; }
BIND(void, jcl_java_io_StreamTokenizer_resetSyntax, "java.io.StreamTokenizer_resetSyntax", (void *self)) { (void)self; }
BIND(void, jcl_java_io_StreamTokenizer_whitespaceChars, "java.io.StreamTokenizer_whitespaceChars", (void *self, int32_t a, int32_t b)) { (void)self; (void)a; (void)b; }
BIND(void, jcl_java_io_StreamTokenizer_wordChars, "java.io.StreamTokenizer_wordChars", (void *self, int32_t a, int32_t b)) { (void)self; (void)a; (void)b; }
BIND(int32_t, jcl_java_io_StreamTokenizer_nextToken, "java.io.StreamTokenizer_nextToken", (void *self)) { (void)self; return -1; }

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
BIND(void, jcl_java_lang_Thread_init, "java_lang_Thread_init", (void *self, void *name)) { (void)self; (void)name; }
BIND(void, jcl_java_lang_Runnable_init, "java_lang_Runnable_init", (void *self)) { (void)self; }
BIND(void, jcl_java_lang_IllegalArgumentException_init, "java_lang_IllegalArgumentException_init", (void *self, void *msg)) {
    jcl_fill_throw(self, "java/lang/IllegalArgumentException", "java/lang/RuntimeException", jcl_str(msg));
}
BIND(void, jcl_java_lang_IllegalStateException_init, "java_lang_IllegalStateException_init", (void *self, void *msg)) {
    jcl_fill_throw(self, "java/lang/IllegalStateException", "java/lang/RuntimeException", jcl_str(msg));
}
BIND(void, jcl_java_lang_NullPointerException_init, "java_lang_NullPointerException_init", (void *self, void *msg)) {
    jcl_fill_throw(self, "java/lang/NullPointerException", "java/lang/RuntimeException", jcl_str(msg));
}
BIND(void, jcl_java_lang_UnsupportedOperationException_init, "java_lang_UnsupportedOperationException_init", (void *self)) {
    jcl_fill_throw(self, "java/lang/UnsupportedOperationException", "java/lang/RuntimeException", "");
}
BIND(void, jcl_java_lang_UnsupportedOperationException_init_PP523beb0a, "java_lang_UnsupportedOperationException_init$PP523beb0a", (void *self, void *msg)) {
    jcl_fill_throw(self, "java/lang/UnsupportedOperationException", "java/lang/RuntimeException", jcl_str(msg));
}
BIND(void, jcl_java_util_NoSuchElementException_init, "java_util_NoSuchElementException_init", (void *self, void *msg)) {
    jcl_fill_throw(self, "java/util/NoSuchElementException", "java/lang/RuntimeException", jcl_str(msg));
}
BIND(void, jcl_java_math_BigDecimal_init, "java_math_BigDecimal_init", (void *self, void *s)) {
    if (self == NULL) return;
    s_str *st = (s_str *)jcl_alloc(sizeof(s_str));
    st->kind = K_STR;
    st->v = jcl_dup(jcl_str(s));
    nabu_jcl_set_state(self, st);
}
BIND(void, jcl_java_math_BigInteger_init, "java_math_BigInteger_init", (void *self, void *s)) { jcl_java_math_BigDecimal_init(self, s); }
BIND(void, jcl_java_net_URI_init, "java_net_URI_init", (void *self, void *s)) { jcl_java_math_BigDecimal_init(self, s); }
BIND(void, jcl_java_net_URL_init, "java_net_URL_init", (void *self, void *s)) { jcl_java_math_BigDecimal_init(self, s); }
BIND(void, jcl_java_text_SimpleDateFormat_init, "java_text_SimpleDateFormat_init", (void *self, void *pat)) { (void)self; (void)pat; }


static void *jcl_new_obj(const char *type, void *state) {
    void *obj = jcl_alloc(48 + 8);
    nabu_init_object(obj, type);
    if (state != NULL) nabu_jcl_set_state(obj, state);
    return obj;
}

static s_list *jcl_list_of(void *self) {
    s_list *l = (s_list *)jcl_state_kind(self, K_LIST);
    if (l == NULL) {
        l = (s_list *)jcl_alloc(sizeof(s_list));
        l->kind = K_LIST;
        l->cap = 8;
        l->it = (void **)jcl_alloc(sizeof(void *) * 8);
        if (self != NULL && nabu_jcl_is_object(self)) nabu_jcl_set_state(self, l);
    }
    return l;
}
static s_set *jcl_set_of(void *self) {
    s_set *s = (s_set *)jcl_state_kind(self, K_SET);
    if (s == NULL) {
        s = (s_set *)jcl_alloc(sizeof(s_set));
        s->kind = K_SET;
        s->cap = 8;
        s->it = (void **)jcl_alloc(sizeof(void *) * 8);
        if (self != NULL && nabu_jcl_is_object(self)) nabu_jcl_set_state(self, s);
    }
    return s;
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
static void jcl_list_add(s_list *l, void *v) {
    if (l->size == l->cap) {
        l->cap *= 2;
        l->it = (void **)JCL_REALLOC(l->it, sizeof(void *) * (size_t)l->cap);
    }
    l->it[l->size++] = v;
}
static void jcl_list_ins(s_list *l, int32_t i, void *v) {
    if (i < 0) i = 0;
    if (i > l->size) i = l->size;
    jcl_list_add(l, NULL);
    memmove(&l->it[i + 1], &l->it[i], sizeof(void *) * (size_t)(l->size - i - 1));
    l->it[i] = v;
}
static int jcl_list_idx(s_list *l, void *v) {
    for (int32_t i = 0; i < l->size; i++) if (jcl_eq(l->it[i], v)) return (int)i;
    return -1;
}
static void jcl_list_rem(s_list *l, int32_t i) {
    if (i < 0 || i >= l->size) return;
    memmove(&l->it[i], &l->it[i + 1], sizeof(void *) * (size_t)(l->size - i - 1));
    l->size--;
}
static int jcl_set_add(s_set *s, void *v) {
    for (int32_t i = 0; i < s->size; i++) if (jcl_eq(s->it[i], v)) return 0;
    if (s->size == s->cap) {
        s->cap *= 2;
        s->it = (void **)JCL_REALLOC(s->it, sizeof(void *) * (size_t)s->cap);
    }
    s->it[s->size++] = v;
    return 1;
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
static void jcl_map_rem(void *map, void *k) {
    s_map *m = (s_map *)jcl_state_kind(map, K_MAP);
    if (m == NULL) return;
    for (int32_t i = 0; i < m->size; i++) {
        if (jcl_eq(m->ks[i], k)) {
            memmove(&m->ks[i], &m->ks[i + 1], sizeof(void *) * (size_t)(m->size - i - 1));
            memmove(&m->vs[i], &m->vs[i + 1], sizeof(void *) * (size_t)(m->size - i - 1));
            m->size--;
            return;
        }
    }
}
static void *jcl_iter_obj(void **items, int32_t n) {
    s_iter *it = (s_iter *)jcl_alloc(sizeof(s_iter));
    it->kind = K_ITER;
    it->items = items;
    it->size = n;
    it->idx = 0;
    return jcl_new_obj("java/util/Iterator", it);
}
static void *jcl_list_view(s_list *l) {
    s_list *v = (s_list *)jcl_alloc(sizeof(s_list));
    v->kind = K_VIEW;
    v->it = l->it;
    v->size = l->size;
    v->cap = l->cap;
    return jcl_new_obj("java/util/ArrayList", v);
}
static s_list *jcl_view_list(void *self) {
    s_list *l = (s_list *)jcl_state_kind(self, K_VIEW);
    if (l == NULL) l = jcl_list_of(self);
    return l;
}

BIND(void, jcl_java_util_ArrayList_init, "java_util_ArrayList_init", (void *self)) { jcl_list_of(self); }
BIND(void, jcl_java_util_ArrayList_init_PI32, "java_util_ArrayList_init$PI32", (void *self, int32_t cap)) { (void)cap; jcl_list_of(self); }
BIND(void, jcl_java_util_ArrayList_init_PP5036ee31, "java_util_ArrayList_init$PP5036ee31", (void *self, void *coll)) {
    s_list *l = jcl_list_of(self);
    if (coll != NULL && nabu_jcl_is_object(coll)) {
        s_list *c = (s_list *)jcl_state_kind(coll, K_LIST);
        if (c == NULL) c = (s_list *)jcl_state_kind(coll, K_VIEW);
        if (c == NULL) c = (s_list *)jcl_state_kind(coll, K_SET);
        if (c != NULL) for (int32_t i = 0; i < c->size; i++) jcl_list_add(l, c->it[i]);
    }
}
BIND(int, jcl_java_util_List_add, "java.util.List_add", (void *self, void *v)) { jcl_list_add(jcl_list_of(self), v); return 1; }
BIND(void, jcl_java_util_List_add_PI32P5f790d9c, "java.util.List_add$PI32P5f790d9c", (void *self, int32_t i, void *v)) { jcl_list_ins(jcl_list_of(self), i, v); }
BIND(int, jcl_java_util_List_addAll, "java.util.List_addAll", (void *self, void *coll)) {
    s_list *l = jcl_list_of(self);
    s_list *c = NULL;
    if (coll != NULL && nabu_jcl_is_object(coll)) {
        c = (s_list *)jcl_state_kind(coll, K_LIST);
        if (c == NULL) c = (s_list *)jcl_state_kind(coll, K_VIEW);
        if (c == NULL) c = (s_list *)jcl_state_kind(coll, K_SET);
    }
    if (c == NULL) return 0;
    for (int32_t i = 0; i < c->size; i++) jcl_list_add(l, c->it[i]);
    return 1;
}
BIND(void, jcl_java_util_List_clear, "java.util.List_clear", (void *self)) { jcl_list_of(self)->size = 0; }
BIND(int, jcl_java_util_List_contains, "java.util.List_contains", (void *self, void *v)) { return jcl_list_idx(jcl_list_of(self), v) >= 0; }
BIND(int, jcl_java_util_List_equals, "java.util.List_equals", (void *self, void *o)) {
    s_list *a = jcl_list_of(self);
    s_list *b = (o != NULL && nabu_jcl_is_object(o)) ? (s_list *)jcl_state_kind(o, K_LIST) : NULL;
    if (b == NULL) return self == o;
    if (a->size != b->size) return 0;
    for (int32_t i = 0; i < a->size; i++) if (!jcl_eq(a->it[i], b->it[i])) return 0;
    return 1;
}
BIND(void *, jcl_java_util_List_get, "java.util.List_get", (void *self, int32_t i)) {
    s_list *l = jcl_list_of(self);
    if (i < 0 || i >= l->size) { jcl_throw("java/lang/IndexOutOfBoundsException", "java/lang/RuntimeException", "get"); return NULL; }
    return l->it[i];
}
BIND(int32_t, jcl_java_util_List_hashCode, "java.util.List_hashCode", (void *self)) {
    s_list *l = jcl_list_of(self);
    int32_t h = 1;
    for (int32_t i = 0; i < l->size; i++) h = 31 * h + jcl_hash(l->it[i]);
    return h;
}
BIND(int32_t, jcl_java_util_List_indexOf, "java.util.List_indexOf", (void *self, void *v)) { return jcl_list_idx(jcl_list_of(self), v); }
BIND(int, jcl_java_util_List_isEmpty, "java.util.List_isEmpty", (void *self)) { return jcl_list_of(self)->size == 0; }
BIND(void *, jcl_java_util_List_iterator, "java.util.List_iterator", (void *self)) {
    s_list *l = jcl_list_of(self);
    return jcl_iter_obj(l->it, l->size);
}
BIND(int, jcl_java_util_List_remove, "java.util.List_remove", (void *self, void *v)) {
    s_list *l = jcl_list_of(self);
    int i = jcl_list_idx(l, v);
    if (i < 0) return 0;
    jcl_list_rem(l, i);
    return 1;
}
BIND(void *, jcl_java_util_List_remove_PI32, "java.util.List_remove$PI32", (void *self, int32_t i)) {
    s_list *l = jcl_list_of(self);
    if (i < 0 || i >= l->size) return NULL;
    void *v = l->it[i];
    jcl_list_rem(l, i);
    return v;
}
BIND(int, jcl_java_util_List_removeAll, "java.util.List_removeAll", (void *self, void *coll)) {
    s_list *l = jcl_list_of(self);
    s_list *c = NULL;
    if (coll != NULL && nabu_jcl_is_object(coll)) {
        c = (s_list *)jcl_state_kind(coll, K_LIST);
        if (c == NULL) c = (s_list *)jcl_state_kind(coll, K_VIEW);
        if (c == NULL) c = (s_list *)jcl_state_kind(coll, K_SET);
    }
    if (c == NULL) return 0;
    int changed = 0;
    for (int32_t i = 0; i < l->size; ) {
        int hit = 0;
        for (int32_t j = 0; j < c->size; j++) if (jcl_eq(l->it[i], c->it[j])) { hit = 1; break; }
        if (hit) { jcl_list_rem(l, i); changed = 1; } else i++;
    }
    return changed;
}
BIND(int32_t, jcl_java_util_List_size, "java.util.List_size", (void *self)) { return jcl_list_of(self)->size; }
BIND(void *, jcl_java_util_List_subList, "java.util.List_subList", (void *self, int32_t from, int32_t to)) {
    s_list *l = jcl_list_of(self);
    s_list *v = (s_list *)jcl_alloc(sizeof(s_list));
    v->kind = K_VIEW;
    v->cap = 8;
    v->it = (void **)jcl_alloc(sizeof(void *) * 8);
    v->size = 0;
    if (from < 0) from = 0;
    if (to > l->size) to = l->size;
    for (int32_t i = from; i < to; i++) jcl_list_add(v, l->it[i]);
    return jcl_new_obj("java/util/ArrayList", v);
}
BIND(void *, jcl_java_util_List_toArray, "java.util.List_toArray", (void *self, void *a)) {
    s_list *l = jcl_list_of(self);
    (void)a;
    void *out = jcl_arr_ref(l->size);
    for (int32_t i = 0; i < l->size; i++) jcl_arr_set(out, i, l->it[i]);
    return out;
}
BIND(int, jcl_java_util_Collection_add, "java.util.Collection_add", (void *self, void *v)) {
    void *st = nabu_jcl_is_object(self) ? nabu_jcl_state(self) : NULL;
    if (st == NULL) return 0;
    int k = *(int *)st;
    if (k == K_SET) return jcl_set_add((s_set *)st, v);
    jcl_list_add((s_list *)st, v);
    return 1;
}
BIND(int, jcl_java_util_Collection_addAll, "java.util.Collection_addAll", (void *self, void *coll)) { return jcl_java_util_List_addAll(self, coll); }
BIND(int, jcl_java_util_Collection_isEmpty, "java.util.Collection_isEmpty", (void *self)) {
    void *st = nabu_jcl_is_object(self) ? nabu_jcl_state(self) : NULL;
    if (st == NULL) return 1;
    int k = *(int *)st;
    if (k == K_SET) return ((s_set *)st)->size == 0;
    if (k == K_MAP) return ((s_map *)st)->size == 0;
    if (k == K_LIST || k == K_VIEW) return ((s_list *)st)->size == 0;
    return 1;
}
BIND(void *, jcl_java_util_Collection_iterator, "java.util.Collection_iterator", (void *self)) {
    void *st = nabu_jcl_is_object(self) ? nabu_jcl_state(self) : NULL;
    if (st == NULL) return jcl_iter_obj(NULL, 0);
    int k = *(int *)st;
    if (k == K_SET) { s_set *s = (s_set *)st; return jcl_iter_obj(s->it, s->size); }
    if (k == K_MAP) { s_map *m = (s_map *)st; return jcl_iter_obj(m->ks, m->size); }
    s_list *l = (s_list *)st;
    return jcl_iter_obj(l->it, l->size);
}
BIND(int, jcl_java_util_Collection_remove, "java.util.Collection_remove", (void *self, void *v)) {
    void *st = nabu_jcl_is_object(self) ? nabu_jcl_state(self) : NULL;
    if (st == NULL) return 0;
    int k = *(int *)st;
    if (k == K_SET) {
        s_set *s = (s_set *)st;
        for (int32_t i = 0; i < s->size; i++) if (jcl_eq(s->it[i], v)) {
            memmove(&s->it[i], &s->it[i + 1], sizeof(void *) * (size_t)(s->size - i - 1));
            s->size--;
            return 1;
        }
        return 0;
    }
    return jcl_java_util_List_remove(self, v);
}
BIND(int, jcl_java_util_Iterator_hasNext, "java.util.Iterator_hasNext", (void *self)) {
    s_iter *it = (s_iter *)jcl_state_kind(self, K_ITER);
    if (it == NULL) {
        s_list *l = jcl_state_kind(self, K_LIST);
        if (l == NULL) l = jcl_state_kind(self, K_VIEW);
        if (l != NULL) return l->size > 0;
        return 0;
    }
    return it->idx < it->size;
}
BIND(void *, jcl_java_util_Iterator_next, "java.util.Iterator_next", (void *self)) {
    s_iter *it = (s_iter *)jcl_state_kind(self, K_ITER);
    if (it != NULL) {
        if (it->idx >= it->size) { jcl_throw("java/util/NoSuchElementException", "java/lang/RuntimeException", "next"); return NULL; }
        return it->items[it->idx++];
    }
    s_list *l = (s_list *)jcl_state_kind(self, K_LIST);
    if (l != NULL && l->size > 0) return l->it[--l->size];
    return NULL;
}

BIND(void, jcl_java_util_HashMap_init, "java_util_HashMap_init", (void *self)) { jcl_map_of(self); }
BIND(void, jcl_java_util_HashMap_init_PPa567c40b, "java_util_HashMap_init$PPa567c40b", (void *self, void *m)) {
    jcl_map_of(self);
    if (m != NULL && nabu_jcl_is_object(m)) {
        s_map *src = (s_map *)jcl_state_kind(m, K_MAP);
        if (src != NULL) for (int32_t i = 0; i < src->size; i++) jcl_map_put(self, src->ks[i], src->vs[i]);
    }
}
BIND(void, jcl_java_util_LinkedHashMap_init, "java_util_LinkedHashMap_init", (void *self)) { jcl_map_of(self); }
BIND(void, jcl_java_util_LinkedHashMap_init_PI32, "java_util_LinkedHashMap_init$PI32", (void *self, int32_t cap)) { (void)cap; jcl_map_of(self); }
BIND(void, jcl_java_util_LinkedHashMap_init_PPa567c40b, "java_util_LinkedHashMap_init$PPa567c40b", (void *self, void *m)) { jcl_java_util_HashMap_init_PPa567c40b(self, m); }
BIND(void, jcl_java_util_IdentityHashMap_init, "java_util_IdentityHashMap_init", (void *self)) { jcl_map_of(self); }
BIND(void, jcl_java_util_TreeMap_init, "java_util_TreeMap_init", (void *self)) { jcl_map_of(self); }
BIND(void, jcl_java_util_Properties_init, "java_util_Properties_init", (void *self)) { jcl_map_of(self); }
static void * jcl_java_util_HashMap_put (void *self, void *k, void *v) {
    void *old = jcl_map_get(self, k);
    jcl_map_put(self, k, v);
    return old;
}
static void jcl_java_util_HashMap_putAll (void *self, void *m) {
    if (m != NULL && nabu_jcl_is_object(m)) {
        s_map *src = (s_map *)jcl_state_kind(m, K_MAP);
        if (src != NULL) for (int32_t i = 0; i < src->size; i++) jcl_map_put(self, src->ks[i], src->vs[i]);
    }
}
static void * jcl_java_util_HashMap_remove (void *self, void *k) {
    void *old = jcl_map_get(self, k);
    jcl_map_rem(self, k);
    return old;
}
static void *jcl_map_keys(void *self) {
    s_map *m = jcl_map_of(self);
    s_list *v = (s_list *)jcl_alloc(sizeof(s_list));
    v->kind = K_VIEW;
    v->cap = m->cap;
    v->it = (void **)jcl_alloc(sizeof(void *) * (size_t)(m->cap > 0 ? m->cap : 8));
    v->size = 0;
    for (int32_t i = 0; i < m->size; i++) jcl_list_add(v, m->ks[i]);
    return jcl_new_obj("java/util/ArrayList", v);
}
static void *jcl_map_vals(void *self) {
    s_map *m = jcl_map_of(self);
    s_list *v = (s_list *)jcl_alloc(sizeof(s_list));
    v->kind = K_VIEW;
    v->cap = m->cap;
    v->it = (void **)jcl_alloc(sizeof(void *) * (size_t)(m->cap > 0 ? m->cap : 8));
    v->size = 0;
    for (int32_t i = 0; i < m->size; i++) jcl_list_add(v, m->vs[i]);
    return jcl_new_obj("java/util/ArrayList", v);
}
static void *jcl_map_entries(void *self) {
    s_map *m = jcl_map_of(self);
    void *arr = jcl_arr_ref(m->size);
    for (int32_t i = 0; i < m->size; i++) {
        s_ent *e = (s_ent *)jcl_alloc(sizeof(s_ent));
        e->kind = K_ENTRY;
        e->k = m->ks[i];
        e->v = m->vs[i];
        jcl_arr_set(arr, i, jcl_new_obj("java/util/Map$Entry", e));
    }
    s_list *v = (s_list *)jcl_alloc(sizeof(s_list));
    v->kind = K_VIEW;
    v->cap = m->size > 0 ? m->size : 8;
    v->it = (void **)jcl_alloc(sizeof(void *) * (size_t)v->cap);
    v->size = 0;
    for (int32_t i = 0; i < m->size; i++) jcl_list_add(v, jcl_arr_elem(arr, i));
    return jcl_new_obj("java/util/ArrayList", v);
}
BIND(void *, jcl_java_util_Map_Entry_getKey, "java.util.Map$Entry_getKey", (void *self)) {
    s_ent *e = (s_ent *)jcl_state_kind(self, K_ENTRY);
    return e != NULL ? e->k : NULL;
}
BIND(void *, jcl_java_util_Map_Entry_getValue, "java.util.Map$Entry_getValue", (void *self)) {
    s_ent *e = (s_ent *)jcl_state_kind(self, K_ENTRY);
    return e != NULL ? e->v : NULL;
}
BIND(void, jcl_java_util_Map_clear, "java.util.Map_clear", (void *self)) { jcl_map_of(self)->size = 0; }
BIND(int, jcl_java_util_Map_containsKey, "java.util.Map_containsKey", (void *self, void *k)) { return jcl_map_get(self, k) != NULL; }
BIND(void *, jcl_java_util_Map_entrySet, "java.util.Map_entrySet", (void *self)) { return jcl_map_entries(self); }
BIND(int, jcl_java_util_Map_equals, "java.util.Map_equals", (void *self, void *o)) {
    s_map *a = (s_map *)jcl_state_kind(self, K_MAP);
    s_map *b = (o != NULL && nabu_jcl_is_object(o)) ? (s_map *)jcl_state_kind(o, K_MAP) : NULL;
    if (a == NULL || b == NULL) return self == o;
    if (a->size != b->size) return 0;
    for (int32_t i = 0; i < a->size; i++) {
        void *v = jcl_map_get(o, a->ks[i]);
        if (v == NULL || !jcl_eq(v, a->vs[i])) return 0;
    }
    return 1;
}
BIND(void *, jcl_java_util_Map_get, "java.util.Map_get", (void *self, void *k)) { return jcl_map_get(self, k); }
BIND(int32_t, jcl_java_util_Map_hashCode, "java.util.Map_hashCode", (void *self)) {
    s_map *m = (s_map *)jcl_state_kind(self, K_MAP);
    int32_t h = 0;
    if (m != NULL) for (int32_t i = 0; i < m->size; i++) h += jcl_hash(m->ks[i]) ^ jcl_hash(m->vs[i]);
    return h;
}
BIND(int, jcl_java_util_Map_isEmpty, "java.util.Map_isEmpty", (void *self)) {
    s_map *m = (s_map *)jcl_state_kind(self, K_MAP);
    return m == NULL || m->size == 0;
}
BIND(void *, jcl_java_util_Map_keySet, "java.util.Map_keySet", (void *self)) { return jcl_map_keys(self); }
BIND(void *, jcl_java_util_Map_put, "java.util.Map_put", (void *self, void *k, void *v)) { return jcl_java_util_HashMap_put(self, k, v); }
BIND(void, jcl_java_util_Map_putAll, "java.util.Map_putAll", (void *self, void *m)) { jcl_java_util_HashMap_putAll(self, m); }
BIND(void *, jcl_java_util_Map_remove, "java.util.Map_remove", (void *self, void *k)) { return jcl_java_util_HashMap_remove(self, k); }
BIND(int32_t, jcl_java_util_Map_size, "java.util.Map_size", (void *self)) {
    s_map *m = (s_map *)jcl_state_kind(self, K_MAP);
    return m != NULL ? m->size : 0;
}
BIND(void *, jcl_java_util_Map_values, "java.util.Map_values", (void *self)) { return jcl_map_vals(self); }

BIND(void, jcl_java_util_HashSet_init, "java_util_HashSet_init", (void *self, void *coll)) {
    s_set *s = jcl_set_of(self);
    if (coll != NULL && nabu_jcl_is_object(coll)) {
        s_list *c = (s_list *)jcl_state_kind(coll, K_LIST);
        if (c == NULL) c = (s_list *)jcl_state_kind(coll, K_VIEW);
        if (c != NULL) for (int32_t i = 0; i < c->size; i++) jcl_set_add(s, c->it[i]);
    }
}
BIND(void, jcl_java_util_HashSet_init_P, "java_util_HashSet_init$P", (void *self, void *coll)) { jcl_java_util_HashSet_init(self, coll); }
BIND(void, jcl_java_util_LinkedHashSet_init, "java_util_LinkedHashSet_init", (void *self, void *coll)) { jcl_java_util_HashSet_init(self, coll); }
BIND(void, jcl_java_util_LinkedHashSet_init_PP5036ee31, "java_util_LinkedHashSet_init$PP5036ee31", (void *self, void *coll)) { jcl_java_util_HashSet_init(self, coll); }
BIND(void, jcl_java_util_TreeSet_init, "java_util_TreeSet_init", (void *self)) { jcl_set_of(self); }
BIND(int, jcl_java_util_Set_add, "java.util.Set_add", (void *self, void *v)) { return jcl_set_add(jcl_set_of(self), v); }
BIND(int, jcl_java_util_Set_addAll, "java.util.Set_addAll", (void *self, void *coll)) {
    s_set *s = jcl_set_of(self);
    s_list *c = NULL;
    if (coll != NULL && nabu_jcl_is_object(coll)) {
        c = (s_list *)jcl_state_kind(coll, K_LIST);
        if (c == NULL) c = (s_list *)jcl_state_kind(coll, K_VIEW);
        if (c == NULL) c = (s_list *)jcl_state_kind(coll, K_SET);
    }
    if (c == NULL) return 0;
    int changed = 0;
    for (int32_t i = 0; i < c->size; i++) changed |= jcl_set_add(s, c->it[i]);
    return changed;
}
BIND(int, jcl_java_util_Set_contains, "java.util.Set_contains", (void *self, void *v)) {
    s_set *s = jcl_set_of(self);
    for (int32_t i = 0; i < s->size; i++) if (jcl_eq(s->it[i], v)) return 1;
    return 0;
}
BIND(int, jcl_java_util_Set_isEmpty, "java.util.Set_isEmpty", (void *self)) { return jcl_set_of(self)->size == 0; }
BIND(void *, jcl_java_util_Set_iterator, "java.util.Set_iterator", (void *self)) {
    s_set *s = jcl_set_of(self);
    return jcl_iter_obj(s->it, s->size);
}
BIND(int, jcl_java_util_Set_removeAll, "java.util.Set_removeAll", (void *self, void *coll)) {
    s_set *s = jcl_set_of(self);
    s_list *c = NULL;
    if (coll != NULL && nabu_jcl_is_object(coll)) {
        c = (s_list *)jcl_state_kind(coll, K_LIST);
        if (c == NULL) c = (s_list *)jcl_state_kind(coll, K_VIEW);
        if (c == NULL) c = (s_list *)jcl_state_kind(coll, K_SET);
    }
    if (c == NULL) return 0;
    int changed = 0;
    for (int32_t j = 0; j < c->size; j++) {
        for (int32_t i = 0; i < s->size; i++) if (jcl_eq(s->it[i], c->it[j])) {
            memmove(&s->it[i], &s->it[i + 1], sizeof(void *) * (size_t)(s->size - i - 1));
            s->size--;
            changed = 1;
            break;
        }
    }
    return changed;
}
BIND(int, jcl_java_util_Set_retainAll, "java.util.Set_retainAll", (void *self, void *coll)) {
    s_set *s = jcl_set_of(self);
    s_list *c = NULL;
    if (coll != NULL && nabu_jcl_is_object(coll)) {
        c = (s_list *)jcl_state_kind(coll, K_LIST);
        if (c == NULL) c = (s_list *)jcl_state_kind(coll, K_VIEW);
        if (c == NULL) c = (s_list *)jcl_state_kind(coll, K_SET);
    }
    if (c == NULL) return 0;
    int changed = 0;
    for (int32_t i = 0; i < s->size; ) {
        int keep = 0;
        for (int32_t j = 0; j < c->size; j++) if (jcl_eq(s->it[i], c->it[j])) { keep = 1; break; }
        if (!keep) { memmove(&s->it[i], &s->it[i + 1], sizeof(void *) * (size_t)(s->size - i - 1)); s->size--; changed = 1; } else i++;
    }
    return changed;
}
BIND(int32_t, jcl_java_util_Set_size, "java.util.Set_size", (void *self)) { return jcl_set_of(self)->size; }
BIND(void *, jcl_java_util_Set_toArray, "java.util.Set_toArray", (void *self, void *a)) {
    (void)a;
    s_set *s = jcl_set_of(self);
    void *out = jcl_arr_ref(s->size);
    for (int32_t i = 0; i < s->size; i++) jcl_arr_set(out, i, s->it[i]);
    return out;
}

BIND(void, jcl_java_util_LinkedList_init, "java_util_LinkedList_init", (void *self)) { jcl_list_of(self); }
BIND(void, jcl_java_util_Stack_init, "java_util_Stack_init", (void *self)) { jcl_list_of(self); }


BIND(int, jcl_java_util_Queue_add, "java.util.Queue_add", (void *self, void *v)) { jcl_list_add(jcl_list_of(self), v); return 1; }
BIND(int, jcl_java_util_Enumeration_hasMoreElements, "java.util.Enumeration_hasMoreElements", (void *self)) { return jcl_java_util_Iterator_hasNext(self); }
BIND(void *, jcl_java_util_Enumeration_nextElement, "java.util.Enumeration_nextElement", (void *self)) { return jcl_java_util_Iterator_next(self); }

static void jcl_sort_strs(void **a, int32_t n) {
    for (int32_t i = 1; i < n; i++) {
        void *key = a[i];
        int32_t j = i - 1;
        const char *ks = jcl_str(key);
        while (j >= 0 && strcmp(jcl_str(a[j]), ks) > 0) { a[j + 1] = a[j]; j--; }
        a[j + 1] = key;
    }
}
BIND(void, jcl_java_util_Comparator_init, "java_util_Comparator_init", (void *self)) { (void)self; }

BIND(void *, jcl_java_util_SortedMap_values, "java.util.SortedMap_values", (void *self)) { return jcl_map_vals(self); }
BIND(void *, jcl_java_lang_Iterable_iterator, "java.lang.Iterable_iterator", (void *self)) { return jcl_java_util_Collection_iterator(self); }

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
BIND(void *, jcl_java_lang_Class_enumConstantDirectory, "java.lang.Class_enumConstantDirectory", (void *self)) {
    const nabu_enum_entry *e = jcl_enum_entry(self);
    void *map = jcl_new_obj("java/util/HashMap", NULL);
    jcl_map_of(map);
    if (e == NULL) {
        nabu_throw(jcl_throw("java/lang/IllegalArgumentException", "java/lang/RuntimeException",
                jcl_concat(jcl_str(self), " is not an enum class")));
        return map;
    }
    void *constants = e->values();
    int32_t n = jcl_arr_len(constants);
    for (int32_t i = 0; i < n; i++) {
        void *c = ((void **)((char *)constants + 8))[i];
        intptr_t off = nabu_instance_field_offset(c, "name");
        if (c != NULL && off >= 0) jcl_map_put(map, *(void **)((char *)c + off), c);
    }
    return map;
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
BIND(void, jcl_java_lang_Runnable_run, "java.lang.Runnable_run", (void *self)) { (void)self; }

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
BIND(int, jcl_java_lang_reflect_AnnotatedElement_isAnnotationPresent, "java.lang.reflect.AnnotatedElement_isAnnotationPresent", (void *self, void *ann)) {
    return jcl_java_lang_reflect_AnnotatedElement_getAnnotation(self, ann) != NULL;
}
BIND(void *, jcl_java_lang_reflect_Array_get, "java.lang.reflect.Array_get", (void *a, int32_t i)) { return jcl_arr_elem(a, i); }
BIND(int32_t, jcl_java_lang_reflect_Array_getLength, "java.lang.reflect.Array_getLength", (void *a)) { return jcl_arr_len(a); }
BIND(void *, jcl_java_lang_reflect_Array_newInstance, "java.lang.reflect.Array_newInstance", (void *cls, int32_t n)) { (void)cls; return jcl_arr_ref(n); }
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
static void * jcl_java_lang_reflect_Method_getName (void *self) { (void)self; return (void *)""; }
BIND(void *, jcl_java_lang_reflect_Method_getName_jstr, "java.lang.reflect.Method_getName", (void *self)) {
    void *r = (void *)jcl_java_lang_reflect_Method_getName(self);
    return r == NULL ? NULL : nabu_jstring_new(nabu_cstr(r));
}
BIND(void *, jcl_java_lang_reflect_Method_getDeclaringClass, "java.lang.reflect.Method_getDeclaringClass", (void *self)) { (void)self; return jcl_class(""); }
BIND(void *, jcl_java_lang_reflect_Method_getReturnType, "java.lang.reflect.Method_getReturnType", (void *self)) { (void)self; return jcl_class("void"); }
BIND(void *, jcl_java_lang_reflect_Method_getGenericReturnType, "java.lang.reflect.Method_getGenericReturnType", (void *self)) { (void)self; return jcl_class("void"); }
BIND(void *, jcl_java_lang_reflect_Method_getParameterTypes, "java.lang.reflect.Method_getParameterTypes", (void *self)) { (void)self; return jcl_arr_ref(0); }
BIND(void *, jcl_java_lang_reflect_Method_getGenericParameterTypes, "java.lang.reflect.Method_getGenericParameterTypes", (void *self)) { (void)self; return jcl_arr_ref(0); }
BIND(void *, jcl_java_lang_reflect_Method_getParameterAnnotations, "java.lang.reflect.Method_getParameterAnnotations", (void *self)) { (void)self; return jcl_arr_ref(0); }
BIND(int32_t, jcl_java_lang_reflect_Method_getModifiers, "java.lang.reflect.Method_getModifiers", (void *self)) { (void)self; return 1; }
BIND(void *, jcl_java_lang_reflect_Method_invoke, "java.lang.reflect.Method_invoke", (void *self, void *tgt, void *args)) { (void)self; (void)tgt; (void)args; return NULL; }
BIND(void, jcl_java_lang_reflect_Method_setAccessible, "java.lang.reflect.Method_setAccessible", (void *self, int v)) { (void)self; (void)v; }
static void * jcl_java_lang_reflect_Method_toString (void *self) { (void)self; return (void *)""; }
BIND(void *, jcl_java_lang_reflect_Method_toString_jstr, "java.lang.reflect.Method_toString", (void *self)) {
    void *r = (void *)jcl_java_lang_reflect_Method_toString(self);
    return r == NULL ? NULL : nabu_jstring_new(nabu_cstr(r));
}
static void * jcl_java_lang_reflect_Method_toGenericString (void *self) { (void)self; return (void *)""; }
BIND(void *, jcl_java_lang_reflect_Method_toGenericString_jstr, "java.lang.reflect.Method_toGenericString", (void *self)) {
    void *r = (void *)jcl_java_lang_reflect_Method_toGenericString(self);
    return r == NULL ? NULL : nabu_jstring_new(nabu_cstr(r));
}
BIND(void *, jcl_java_lang_reflect_ParameterizedType_getActualTypeArguments, "java.lang.reflect.ParameterizedType_getActualTypeArguments", (void *self)) { (void)self; return jcl_arr_ref(0); }
BIND(void *, jcl_java_lang_reflect_ParameterizedType_getRawType, "java.lang.reflect.ParameterizedType_getRawType", (void *self)) { (void)self; return jcl_class(""); }
BIND(void *, jcl_java_lang_reflect_Proxy_getInvocationHandler, "java.lang.reflect.Proxy_getInvocationHandler", (void *self, void *p)) { (void)self; (void)p; return NULL; }
BIND(int, jcl_java_lang_reflect_Proxy_isProxyClass, "java.lang.reflect.Proxy_isProxyClass", (void *self)) { (void)self; return 0; }
BIND(void *, jcl_java_lang_reflect_Proxy_newProxyInstance, "java.lang.reflect.Proxy_newProxyInstance", (void *cl, void *ifs, void *h)) { (void)cl; (void)ifs; (void)h; return NULL; }
BIND(void *, jcl_java_lang_reflect_WildcardType_getLowerBounds, "java.lang.reflect.WildcardType_getLowerBounds", (void *self)) { (void)self; return jcl_arr_ref(0); }
BIND(void *, jcl_java_lang_reflect_WildcardType_getUpperBounds, "java.lang.reflect.WildcardType_getUpperBounds", (void *self)) { (void)self; return jcl_arr_ref(0); }
BIND(void *, jcl_java_lang_reflect_GenericArrayType_getGenericComponentType, "java.lang.reflect.GenericArrayType_getGenericComponentType", (void *self)) { (void)self; return jcl_class(""); }
BIND(void *, jcl_java_lang_reflect_InvocationTargetException_getTargetException, "java.lang.reflect.InvocationTargetException_getTargetException", (void *self)) { return self; }

BIND(int32_t, jcl_IExitCodeExceptionMapper_getExitCode, "IExitCodeExceptionMapper_getExitCode", (void *self, void *ex)) { (void)self; (void)ex; return 1; }
BIND(int32_t, jcl_IExitCodeGenerator_getExitCode, "IExitCodeGenerator_getExitCode", (void *self)) { (void)self; return 0; }
BIND(void *, jcl_IGetter_get, "IGetter_get", (void *self)) { (void)self; return NULL; }
BIND(void *, jcl_ISetter_set, "ISetter_set", (void *self, void *v)) { (void)self; return v; }
BIND(void *, jcl_IHelpSectionRenderer_render, "IHelpSectionRenderer_render", (void *self, void *help)) { (void)self; (void)help; return nabu_jstring_new(""); }
BIND(void *, jcl_ILookup_get, "ILookup_get", (void *self, void *key)) { (void)self; (void)key; return NULL; }
BIND(int32_t, jcl_IParameterExceptionHandler_handleParseException, "IParameterExceptionHandler_handleParseException", (void *self, void *ex, void *cmd)) { (void)self; (void)ex; (void)cmd; return 2; }
BIND(int32_t, jcl_IExecutionExceptionHandler_handleExecutionException, "IExecutionExceptionHandler_handleExecutionException", (void *self, void *ex, void *cmd, void *parseResult)) { (void)self; (void)ex; (void)cmd; (void)parseResult; return 1; }
BIND(void *, jcl_java_lang_reflect_Method_getAnnotation, "java.lang.reflect.Method_getAnnotation", (void *self, void *ann)) { (void)self; (void)ann; return NULL; }
BIND(void *, jcl_java_util_List_set, "java.util.List_set", (void *self, int32_t i, void *v)) {
    s_list *l = jcl_list_of(self);
    if (i < 0 || i >= l->size) return NULL;
    void *old = l->it[i];
    l->it[i] = v;
    return old;
}
BIND(int, jcl_Predicate_test, "Predicate_test", (void *self, void *arg)) { (void)self; (void)arg; return 1; }

BIND(void, jcl_picocli_IStyle_init, "picocli_CommandLine$Help$Ansi$IStyle_init", (void *self)) { (void)self; }
BIND(void, jcl_picocli_IParamLabelRenderer_init, "picocli_CommandLine$Help$IParamLabelRenderer_init", (void *self)) { (void)self; }
BIND(void, jcl_picocli_IExecutionExceptionHandler_init, "picocli_CommandLine$IExecutionExceptionHandler_init", (void *self)) { (void)self; }
BIND(void, jcl_picocli_IHelpSectionRenderer_init, "picocli_CommandLine$IHelpSectionRenderer_init", (void *self)) { (void)self; }
BIND(void, jcl_picocli_IParameterExceptionHandler_init, "picocli_CommandLine$IParameterExceptionHandler_init", (void *self)) { (void)self; }
BIND(void, jcl_picocli_ITypeConverter_init, "picocli_CommandLine$ITypeConverter_init", (void *self)) { (void)self; }
BIND(void, jcl_picocli_ILookup_init, "picocli_CommandLine$Model$Interpolator$ILookup_init", (void *self)) { (void)self; }
BIND(void, jcl_picocli_Predicate_init, "picocli_CommandLine$Model$Predicate_init", (void *self)) { (void)self; }

BIND(void *, jcl_java_util_concurrent_atomic_AtomicInteger_init, "java_util_concurrent_atomic_AtomicInteger_init", (void *self, int32_t v)) {
    s_atomic *s = (s_atomic *)jcl_alloc(sizeof(s_atomic));
    s->kind = K_OTHER;
    s->v = v;
    if (self != NULL) nabu_jcl_set_state(self, s);
    return self;
}
BIND(void *, jcl_java_util_concurrent_Callable_call, "java.util.concurrent.Callable_call", (void *self)) { (void)self; jcl_nyi("java.util.concurrent.Callable_call"); return NULL; }
BIND(void *, jcl_java_util_UUID_fromString, "java.util.UUID_fromString", (void *s)) { return jcl_new_str_obj(jcl_str(s)); }
BIND(void, jcl_java_util_ResourceBundle_init, "java_util_ResourceBundle_init", (void *self)) { (void)self; }


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

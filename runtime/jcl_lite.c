#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#ifdef __APPLE__
/* Before any include: without _XOPEN_SOURCE the ucontext_t of macOS has no room for the machine context that
 * getcontext/makecontext/swapcontext write (the struct is about 50 bytes instead of 900) and they overwrite what follows
 * it. _DARWIN_C_SOURCE keeps the rest of the system interface visible. */
#define _XOPEN_SOURCE 600
#define _DARWIN_C_SOURCE
#endif
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
#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
#include <direct.h>
#include <io.h>
#include <fcntl.h>
#include <sys/stat.h>
#else
#include <unistd.h>
#include <fcntl.h>
#include <signal.h>
#include <sys/wait.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <arpa/inet.h>
#include <netdb.h>
#include <poll.h>
#include <sched.h>
#include <sys/syscall.h>
#ifndef SYS_pidfd_open
#define SYS_pidfd_open 434
#endif
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

/* the symbol of a function the compiled code calls by its JDK name; on macOS an asm label is not given the underscore
 * that every other symbol has */
#ifdef __APPLE__
#define JCL_SYM(sym) "_" sym
#else
#define JCL_SYM(sym) sym
#endif
#define BIND(ret, cname, sym, params) ret cname params __asm__(JCL_SYM(sym)); ret cname params

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
                 int state; int interrupted; int permit; void *self; volatile int wait_state; void *inherit_from;
                 void *group; void *handler; void *container; int is_virtual; void *sv_bindings; void *sv_cache; void *sv_scope;
                 nabu_waiter *waiter;
                 volatile int cap_request; volatile int cap_done; int cap_count; uintptr_t cap_frames[128];
                 nabu_waiter *cap_requester; void *continuation;
                 void *nio_blocker; /* the channel operation the thread is blocked in (Thread.blockedOn) */ } s_thread;
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

/* The state of a Thread: in the word behind the header, also for an object of a compiled subclass of Thread. */
static s_thread *jcl_thread_state(const void *p) {
    if (p == NULL) return NULL;
    void *st = nabu_jcl_state((void *)p);
    return st != NULL && *(int *)st == K_THREAD ? (s_thread *)st : NULL;
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

static void *jcl_class_unlocked(const char *iname);

/* The table grows and gets new entries from every thread: one at a time. */
static void *jcl_class(const char *iname) {
    nabu_rt_lock();
    void *found = jcl_class_unlocked(iname);
    nabu_rt_unlock();
    return found;
}

static void *jcl_class_unlocked(const char *iname) {
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
    {
        /* The type info of Class is made by the runtime, without the interfaces of the real class: a cast of a
         * Class to Type / AnnotatedElement / GenericDeclaration (Hibernate) would fail. */
        static const char *const class_interfaces[] = {
            "java/io/Serializable", "java/lang/reflect/GenericDeclaration", "java/lang/reflect/Type",
            "java/lang/reflect/AnnotatedElement", "java/lang/invoke/TypeDescriptor",
            "java/lang/invoke/TypeDescriptor$OfField", "java/lang/constant/Constable"
        };
        nabu_type_info *class_ti = (nabu_type_info *)((nabu_object *)obj)->type;
        if (class_ti != NULL && class_ti->interface_count == 0) {
            class_ti->interfaces = class_interfaces;
            class_ti->interface_count = (int)(sizeof class_interfaces / sizeof class_interfaces[0]);
        }
    }
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
    const nabu_reflection_info *info;
    char *key = jcl_nested_key(a, nabu_cstr(name), &info);
    const nabu_annotation_entry *e = key != NULL ? jcl_entry_owned(info, key) : NULL;
    if (e != NULL) return jcl_ann_obj(e);
    /* Not in the metadata: the element has its default (@Parameter.schema() is an empty @Schema, never null),
     * an annotation of that type with no values. */
    if (type == NULL) return NULL;
    nabu_annotation_entry *blank = (nabu_annotation_entry *)jcl_alloc(sizeof *blank);
    blank->type_name = jcl_dup(nabu_cstr(type));
    return jcl_ann_obj(blank);
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
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}
/* No security manager (the JDK deprecated it for removal) and no console (stdin/stdout are not a terminal here). */
BIND(void *, jcl_java_lang_System_getSecurityManager, "java.lang.System_getSecurityManager", (void)) { return NULL; }
BIND(void *, jcl_java_lang_System_console, "java.lang.System_console", (void)) { return NULL; }
BIND(int64_t, jcl_java_lang_System_nanoTime, "java.lang.System_nanoTime", (void)) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}
BIND(void, jcl_java_lang_System_exit, "java.lang.System_exit", (int32_t code)) { fflush(NULL); jcl_exiting = 1; exit(code); }
/* ProcessHandleImpl (ProcessHandle.current().pid(), Spring Boot's ApplicationPid): the process of this program. The
 * start time is not known: 0. */
BIND(void, jcl_ProcessHandleImpl_initNative, "java.lang.ProcessHandleImpl_initNative", (void)) { }
BIND(int64_t, jcl_ProcessHandleImpl_getCurrentPid0, "java.lang.ProcessHandleImpl_getCurrentPid0", (void)) {
    return (int64_t)getpid();
}
BIND(int64_t, jcl_ProcessHandleImpl_isAlive0, "java.lang.ProcessHandleImpl_isAlive0", (int64_t pid)) {
    (void)pid;
    return 0;
}
/* StackWalkSupport.mainClass0: the Class of the program's main class (nabu_main_class_name, written by the backend). */
extern const char nabu_main_class_name[] __attribute__((weak));
BIND(void *, jcl_StackWalkSupport_mainClass0, "io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime.StackWalkSupport_mainClass0", (void)) {
    if (&nabu_main_class_name[0] == NULL) return NULL;
    return jcl_class(nabu_main_class_name);
}

/* The time this process started (ManagementSupport: RuntimeMXBean.getStartTime and getUptime). */
static int64_t jcl_process_start_ms = 0;
static void jcl_note_process_start(void) __attribute__((constructor));
static void jcl_note_process_start(void) {
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    jcl_process_start_ms = (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}
BIND(int64_t, jcl_ManagementSupport_startTime0, "io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime.ManagementSupport_startTime0", (void)) {
    return jcl_process_start_ms;
}
/* Loading a native library of the JDK ("management", "net", "nio", ...): the code is part of the program. */
BIND(void, jcl_java_lang_System_loadLibrary, "java.lang.System_loadLibrary", (void *name)) { (void)name; }
BIND(void, jcl_java_lang_System_load, "java.lang.System_load", (void *name)) { (void)name; }

/* EnvSupport.environ0: the environment as a String[] of "NAME=value" (System.getenv()). */
BIND(void *, jcl_EnvSupport_environ0, "io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime.EnvSupport_environ0", (void)) {
    int32_t n = 0;
    if (environ != NULL) while (environ[n] != NULL) n++;
    void *arr = jcl_arr_typed(n, "[Ljava/lang/String;");
    for (int32_t i = 0; i < n; i++) jcl_arr_set(arr, i, nabu_jstring_new(environ[i]));
    return arr;
}
static void * jcl_java_lang_System_getenv (void *name) {
    const char *v = getenv(jcl_str(name));
    /* Kopie: zie jcl_env_or (8-byte-header-herkenning op een getenv-pointer). */
    return v != NULL ? (void *)jcl_dup(v) : NULL;
}
BIND(void *, jcl_java_lang_System_getenv_jstr, "java.lang.System_getenv", (void *name)) {
    void *r = (void *)jcl_java_lang_System_getenv(name);
    return r == NULL ? NULL : nabu_jstring_new(nabu_cstr(r));
}
/* Whether the object is an array (no object header, so no vtable): the backend tests a receiver that is
 * statically an Object before calling equals, hashCode or toString on it. */
int nabu_is_array(void *o) {
    return o != NULL && nabu_compiled_type_name(o) == NULL && jcl_typed_array_name(o) != NULL;
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
static void *jcl_map_remove(void *map, void *k);
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
#ifdef __APPLE__
#include <mach-o/dyld.h>
#endif
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
    /* The directory of the program: the lib directory with the JDK's data files lies next to it. */
#ifdef __APPLE__
    /* no /proc on macOS: the dyld call gives the path, which may be a symbolic link or relative */
    char raw[1024];
    uint32_t raw_size = sizeof raw;
    ssize_t exe_len = -1;
    if (_NSGetExecutablePath(raw, &raw_size) == 0 && realpath(raw, buf) != NULL) exe_len = (ssize_t)strlen(buf);
#else
    ssize_t exe_len = readlink("/proc/self/exe", buf, sizeof buf - 1);
#endif
    if (exe_len > 0) {
        buf[exe_len] = '\0';
        char *slash = strrchr(buf, '/');
        if (slash != NULL) *slash = '\0';
        jcl_map_put(jcl_props, (void *)"java.home", (void *)jcl_dup(buf));
    } else {
        jcl_map_put(jcl_props, (void *)"java.home", (void *)".");
    }
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
    /* As in a GraalVM native image: frameworks (Spring's NativeDetector) take their ahead-of-time paths and skip the
     * class path scanning and module layers a native program does not have. */
    jcl_map_put(jcl_props, (void *)"org.graalvm.nativeimage.imagecode", (void *)"runtime");
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
static void *jcl_props_table_populate(void);

/* Filled once, by the first thread that asks; the others get the finished table. */
static void *jcl_props_table(void) {
    static volatile int ready = 0;
    if (__atomic_load_n(&ready, __ATOMIC_ACQUIRE)) return jcl_props;
    nabu_rt_lock();
    void *table = jcl_props_table_populate();
    __atomic_store_n(&ready, 1, __ATOMIC_RELEASE);
    nabu_rt_unlock();
    return table;
}

static void *jcl_props_table_populate(void) {
    jcl_props_init();
    if (jcl_map_get(jcl_props, "os.name") == NULL) {
#if defined(_WIN32)
        jcl_map_put(jcl_props, (void *)"os.name", (void *)"Windows 11");
        jcl_map_put(jcl_props, (void *)"os.arch", (void *)"amd64");
        jcl_map_put(jcl_props, (void *)"file.separator", (void *)"\\");
        jcl_map_put(jcl_props, (void *)"line.separator", (void *)"\r\n");
        jcl_map_put(jcl_props, (void *)"path.separator", (void *)";");
#else
#if defined(__APPLE__)
        jcl_map_put(jcl_props, (void *)"os.name", (void *)"Mac OS X");
#else
        jcl_map_put(jcl_props, (void *)"os.name", (void *)"Linux");
#endif
#if defined(__aarch64__)
        jcl_map_put(jcl_props, (void *)"os.arch", (void *)"aarch64");
#else
        jcl_map_put(jcl_props, (void *)"os.arch", (void *)"amd64");
#endif
        jcl_map_put(jcl_props, (void *)"file.separator", (void *)"/");
        jcl_map_put(jcl_props, (void *)"line.separator", (void *)"\n");
        jcl_map_put(jcl_props, (void *)"path.separator", (void *)":");
#endif
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
static void *jcl_compiled_properties_build(void);

static void *jcl_compiled_properties(void) {
    if (jcl_compiled_props != NULL) return jcl_compiled_props;
    nabu_rt_lock();
    void *props = jcl_compiled_properties_build();
    nabu_rt_unlock();
    return props;
}

static void *jcl_compiled_properties_build(void) {
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
/* Closes the descriptor in a slot, once: the thread that swaps it for -1 closes it, a second close sees -1. */
static void jcl_fd_close_slot(int32_t *slot) {
    if (slot != NULL && __atomic_load_n(slot, __ATOMIC_ACQUIRE) > 2) {
        const int32_t fd = __atomic_exchange_n(slot, -1, __ATOMIC_ACQ_REL);
        if (fd > 2) {
#ifdef _WIN32
            _close(fd);
#else
            close(fd);
#endif
        }
    }
}
BIND(void, jcl_java_io_FileDescriptor_close0, "java.io.FileDescriptor_close0", (void *self)) {
    jcl_fd_close_slot(jcl_fd_slot(self));
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
#include <sys/statvfs.h>
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
BIND(uint8_t, jcl_fs_checkAccess0, JCL_FS("checkAccess0"), (void *self, void *f, int32_t access_mode)) {
    const char *path = jcl_file_path_of(f);
#ifdef _WIN32
    DWORD a = GetFileAttributesW(jcl_wide(path));
    if (a == INVALID_FILE_ATTRIBUTES) return 0;
    if ((access_mode & 2) && (a & FILE_ATTRIBUTE_READONLY) && !(a & FILE_ATTRIBUTE_DIRECTORY)) return 0;
    return 1;
#else
    int mode = ((access_mode & 4) ? R_OK : 0) | ((access_mode & 2) ? W_OK : 0) | ((access_mode & 1) ? X_OK : 0);
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
    /* Like the JDK: make the path absolute, collapse "." and "..", then resolve links for the longest
     * prefix that exists; the rest does not exist and stays as it is. */
    char full[PATH_MAX * 2], *out = full;
    if (path[0] != '/') {
        if (getcwd(full, PATH_MAX) == NULL) return pathString;
        out = full + strlen(full);
        if (out > full && out[-1] == '/') out--;
    }
    for (const char *p = path; *p != '\0';) {
        while (*p == '/') p++;
        const char *end = p;
        while (*end != '\0' && *end != '/') end++;
        size_t len = (size_t)(end - p);
        if (len == 0) break;
        if (len == 1 && p[0] == '.') {
            /* current directory: nothing */
        } else if (len == 2 && p[0] == '.' && p[1] == '.') {
            while (out > full && out[-1] != '/') out--;
            if (out > full) out--;
        } else {
            if ((size_t)(out - full) + len + 2 >= sizeof full) return pathString;
            *out++ = '/';
            memcpy(out, p, len);
            out += len;
        }
        p = end;
    }
    if (out == full) *out++ = '/';
    *out = '\0';
    char real[PATH_MAX];
    for (char *cut = out;;) {
        char saved = *cut;
        *cut = '\0';
        int found = realpath(cut == full ? "/" : full, real) != NULL;
        *cut = saved;
        if (found) {
            size_t rl = strlen(real);
            if (cut != out) {
                if (rl > 0 && real[rl - 1] == '/') real[--rl] = '\0';
                if (rl + strlen(cut) + 1 >= sizeof real) return pathString;
                strcpy(real + rl, cut);
            }
            return nabu_jstring_new(real);
        }
        while (cut > full && cut[-1] != '/') cut--;
        if (cut > full) cut--;
    }
#endif
}

BIND(int32_t, jcl_fs_getNameMax0, JCL_FS("getNameMax0"), (void *self, void *pathString)) { return 255; }

#ifndef _WIN32
/* --- sun.nio.fs on Linux and macOS: the default file system (UnixFileSystem) only needs the current
 * directory and the capabilities; the file operations of Files go through java.io.File (FilesSubstitution). */
static void *jcl_byte_array(const char *s, size_t n) {
    void *p = jcl_alloc(8 + n);
    ((int32_t *)p)[0] = (int32_t)n;
    ((int32_t *)p)[1] = 1;
    jcl_arr_set_type(p, "[B");
    memcpy((char *)p + 8, s, n);
    return p;
}

/* No optional capability (openat, futimes, ...): the JDK code then takes its plain paths. */
BIND(int32_t, jcl_UnixNativeDispatcher_init, "sun.nio.fs.UnixNativeDispatcher_init", (void)) { return 0; }

BIND(void *, jcl_UnixNativeDispatcher_getcwd, "sun.nio.fs.UnixNativeDispatcher_getcwd", (void)) {
    char buf[4096];
    if (getcwd(buf, sizeof buf) == NULL) { buf[0] = '/'; buf[1] = '\0'; }
    return jcl_byte_array(buf, strlen(buf));
}
#endif

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
    struct statvfs vfs;
    if (statvfs(jcl_file_path_of(f), &vfs) != 0) return 0;
    uint64_t unit = vfs.f_frsize != 0 ? vfs.f_frsize : vfs.f_bsize;
    return t == 0 ? (int64_t)(vfs.f_blocks * unit) : t == 1 ? (int64_t)(vfs.f_bfree * unit) : (int64_t)(vfs.f_bavail * unit);
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
    jcl_fd_close_slot(jcl_fd_slot(fdObj));
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

/* The natives of FileChannel's dispatcher: FileDispatcherImpl on Windows, UnixFileDispatcherImpl (the superclass of
 * FileDispatcherImpl) on Linux and macOS; the signatures are the same. */
#ifdef _WIN32
#define JCL_FDI(name) "sun.nio.ch.FileDispatcherImpl_" name
#else
#define JCL_FDI(name) "sun.nio.ch.UnixFileDispatcherImpl_" name
#endif

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

/* A positional read or write must not move the position of the descriptor that other threads use. Where there is
 * pread/pwrite that is what they do. The CRT has none, so there the seek, the transfer and the seek back are done
 * under a lock that read0, write0 and seek0 take as well. Returns the count, -1 on an error, -2 when the seek failed. */
#ifdef _WIN32
static pthread_mutex_t jcl_position_locks[16] = {
    PTHREAD_MUTEX_INITIALIZER, PTHREAD_MUTEX_INITIALIZER, PTHREAD_MUTEX_INITIALIZER, PTHREAD_MUTEX_INITIALIZER,
    PTHREAD_MUTEX_INITIALIZER, PTHREAD_MUTEX_INITIALIZER, PTHREAD_MUTEX_INITIALIZER, PTHREAD_MUTEX_INITIALIZER,
    PTHREAD_MUTEX_INITIALIZER, PTHREAD_MUTEX_INITIALIZER, PTHREAD_MUTEX_INITIALIZER, PTHREAD_MUTEX_INITIALIZER,
    PTHREAD_MUTEX_INITIALIZER, PTHREAD_MUTEX_INITIALIZER, PTHREAD_MUTEX_INITIALIZER, PTHREAD_MUTEX_INITIALIZER };
static pthread_mutex_t *jcl_position_lock(int fd) { return &jcl_position_locks[(unsigned)fd & 15]; }
#define JCL_POSITION_LOCK(fd) pthread_mutex_lock(jcl_position_lock(fd))
#define JCL_POSITION_UNLOCK(fd) pthread_mutex_unlock(jcl_position_lock(fd))
#else
#define JCL_POSITION_LOCK(fd) ((void)(fd))
#define JCL_POSITION_UNLOCK(fd) ((void)(fd))
#endif

static int jcl_pread(int fd, void *buffer, int len, int64_t position) {
#ifdef _WIN32
    JCL_POSITION_LOCK(fd);
    int64_t saved = JCL_SEEK(fd, 0, SEEK_CUR);
    int n;
    if (JCL_SEEK(fd, position, SEEK_SET) < 0) {
        n = -2;
    } else {
        n = (int)JCL_READ(fd, buffer, len);
        JCL_SEEK(fd, saved, SEEK_SET);
    }
    JCL_POSITION_UNLOCK(fd);
    return n;
#else
    ssize_t n;
    do { n = pread(fd, buffer, (size_t)len, (off_t)position); } while (n < 0 && errno == EINTR);
    return n < 0 && errno == ESPIPE ? -2 : (int)n;
#endif
}

static int jcl_pwrite(int fd, const void *buffer, int len, int64_t position) {
#ifdef _WIN32
    JCL_POSITION_LOCK(fd);
    int64_t saved = JCL_SEEK(fd, 0, SEEK_CUR);
    int n;
    if (JCL_SEEK(fd, position, SEEK_SET) < 0) {
        n = -2;
    } else {
        n = (int)JCL_WRITE(fd, buffer, len);
        JCL_SEEK(fd, saved, SEEK_SET);
    }
    JCL_POSITION_UNLOCK(fd);
    return n;
#else
    ssize_t n;
    do { n = pwrite(fd, buffer, (size_t)len, (off_t)position); } while (n < 0 && errno == EINTR);
    return n < 0 && errno == ESPIPE ? -2 : (int)n;
#endif
}

BIND(int32_t, jcl_FileDispatcherImpl_read0, JCL_FDI("read0"), (void *fdObj, int64_t address, int32_t len)) {
    int fd = jcl_chan_fd(fdObj);
    if (fd < 0) { nabu_throw(jcl_throw("java/io/IOException", "java/lang/Exception", "Stream Closed")); return -1; }
    JCL_POSITION_LOCK(fd);
#ifdef _WIN32
    SetLastError(0);
#endif
    int n = (int)JCL_READ(fd, (void *)(intptr_t)address, len);
#ifdef _WIN32
    const int aborted = n < 0 && GetLastError() == ERROR_OPERATION_ABORTED;
#endif
    JCL_POSITION_UNLOCK(fd);
#ifndef _WIN32
    if (n < 0 && errno == EINTR) return -3; /* IOStatus.INTERRUPTED: the thread was signalled (NativeThread.signal) */
#else
    if (aborted) return -3; /* CancelSynchronousIo (NativeThread.signal) ended the read */
#endif
    if (n < 0) { jcl_chan_error("Read"); return -1; }
    return n == 0 && len > 0 ? -1 : n;
}
BIND(int32_t, jcl_FileDispatcherImpl_pread0, JCL_FDI("pread0"),
     (void *fdObj, int64_t address, int32_t len, int64_t position)) {
    int fd = jcl_chan_fd(fdObj);
    if (fd < 0) { nabu_throw(jcl_throw("java/io/IOException", "java/lang/Exception", "Stream Closed")); return -1; }
    int n = jcl_pread(fd, (void *)(intptr_t)address, len, position);
    if (n == -2) { jcl_chan_error("Seek"); return -1; }
    if (n < 0) { jcl_chan_error("Read"); return -1; }
    return n == 0 && len > 0 ? -1 : n;
}
BIND(int32_t, jcl_FileDispatcherImpl_write0, JCL_FDI("write0"),
     (void *fdObj, int64_t address, int32_t len, uint8_t append)) {
    (void)append;
    int fd = jcl_chan_fd(fdObj);
    if (fd < 0) { nabu_throw(jcl_throw("java/io/IOException", "java/lang/Exception", "Stream Closed")); return -1; }
    JCL_POSITION_LOCK(fd);
    int n = (int)JCL_WRITE(fd, (const void *)(intptr_t)address, len);
    JCL_POSITION_UNLOCK(fd);
#ifndef _WIN32
    if (n < 0 && errno == EINTR) return -3;
#endif
    if (n < 0) { jcl_chan_error("Write"); return -1; }
    return n;
}
BIND(int32_t, jcl_FileDispatcherImpl_pwrite0, JCL_FDI("pwrite0"),
     (void *fdObj, int64_t address, int32_t len, int64_t position)) {
    int fd = jcl_chan_fd(fdObj);
    if (fd < 0) { nabu_throw(jcl_throw("java/io/IOException", "java/lang/Exception", "Stream Closed")); return -1; }
    int n = jcl_pwrite(fd, (const void *)(intptr_t)address, len, position);
    if (n == -2) { jcl_chan_error("Seek"); return -1; }
    if (n < 0) { jcl_chan_error("Write"); return -1; }
    return n;
}
BIND(int64_t, jcl_FileDispatcherImpl_readv0, JCL_FDI("readv0"), (void *fdObj, int64_t address, int32_t len)) {
    (void)fdObj; (void)address; (void)len;
    return -6;
}
BIND(int64_t, jcl_FileDispatcherImpl_writev0, JCL_FDI("writev0"),
     (void *fdObj, int64_t address, int32_t len, uint8_t append)) {
    (void)fdObj; (void)address; (void)len; (void)append;
    return -6;
}
/* offset -1: the current position; otherwise set it. Returns the position. */
BIND(int64_t, jcl_FileDispatcherImpl_seek0, JCL_FDI("seek0"), (void *fdObj, int64_t offset)) {
    int fd = jcl_chan_fd(fdObj);
    if (fd < 0) { nabu_throw(jcl_throw("java/io/IOException", "java/lang/Exception", "Stream Closed")); return -1; }
    JCL_POSITION_LOCK(fd);
    int64_t r = offset < 0 ? JCL_SEEK(fd, 0, SEEK_CUR) : JCL_SEEK(fd, offset, SEEK_SET);
    JCL_POSITION_UNLOCK(fd);
    if (r < 0) { jcl_chan_error("Seek"); return -1; }
    return r;
}
BIND(int32_t, jcl_FileDispatcherImpl_force0, JCL_FDI("force0"), (void *fdObj, uint8_t metaData)) {
    (void)metaData;
    int fd = jcl_chan_fd(fdObj);
#ifdef _WIN32
    if (fd >= 0) _commit(fd);
#else
    if (fd >= 0) fsync(fd);
#endif
    return 0;
}
BIND(int32_t, jcl_FileDispatcherImpl_truncate0, JCL_FDI("truncate0"), (void *fdObj, int64_t size)) {
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
BIND(int64_t, jcl_FileDispatcherImpl_size0, JCL_FDI("size0"), (void *fdObj)) {
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
BIND(int32_t, jcl_FileDispatcherImpl_available0, JCL_FDI("available0"), (void *fdObj)) {
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
BIND(uint8_t, jcl_FileDispatcherImpl_isOther0, JCL_FDI("isOther0"), (void *fdObj)) { (void)fdObj; return 0; }
/* No file locks between processes: the lock is always granted (0 = LOCKED). */
BIND(int32_t, jcl_FileDispatcherImpl_lock0, JCL_FDI("lock0"),
     (void *fdObj, uint8_t blocking, int64_t pos, int64_t size, uint8_t shared)) {
    (void)fdObj; (void)blocking; (void)pos; (void)size; (void)shared;
    return 0;
}
BIND(void, jcl_FileDispatcherImpl_release0, JCL_FDI("release0"), (void *fdObj, int64_t pos, int64_t size)) {
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
BIND(int64_t, jcl_FileDispatcherImpl_allocationGranularity0, JCL_FDI("allocationGranularity0"), (void)) {
    return 65536;
}
BIND(int64_t, jcl_FileDispatcherImpl_map0, JCL_FDI("map0"),
     (void *fdObj, int32_t prot, int64_t position, int64_t length, uint8_t isSync)) {
    (void)fdObj; (void)prot; (void)position; (void)length; (void)isSync;
    nabu_throw(jcl_throw("java/io/IOException", "java/lang/Exception",
            "Memory-mapped files are not supported in a native executable"));
    return 0;
}
BIND(int32_t, jcl_FileDispatcherImpl_unmap0, JCL_FDI("unmap0"), (int64_t address, int64_t length)) {
    (void)address; (void)length;
    return 0;
}
BIND(int64_t, jcl_FileDispatcherImpl_duplicateHandle, "sun.nio.ch.FileDispatcherImpl_duplicateHandle", (int64_t handle)) {
    return handle;
}
BIND(int32_t, jcl_FileDispatcherImpl_setDirect0, JCL_FDI("setDirect0"), (void *fdObj, void *buf)) {
    (void)fdObj; (void)buf;
    return -1;
}
/* The names of these natives are known to RuntimeSymbols (it reads this file), so they are defined on every
 * platform: a native that is only defined on Linux would leave an undefined reference on Windows. */
BIND(void, jcl_FileDispatcherImpl_init0, "sun.nio.ch.FileDispatcherImpl_init0", (void)) {
}
BIND(int64_t, jcl_FileDispatcherImpl_transferFrom0, "sun.nio.ch.FileDispatcherImpl_transferFrom0",
     (void *src, void *dst, int64_t position, int64_t count, uint8_t append)) {
    (void)src; (void)dst; (void)position; (void)count; (void)append;
    return -6; /* UNSUPPORTED_CASE: the JDK copies through a buffer */
}
BIND(void, jcl_UnixFileDispatcherImpl_closeIntFD, "sun.nio.ch.UnixFileDispatcherImpl_closeIntFD", (int32_t fd)) {
    if (fd > 2) {
#ifdef _WIN32
        _close(fd);
#else
        close(fd);
#endif
    }
}
/* A thread that is blocked in a channel operation is woken by a signal (as the JDK does): the handler does nothing and
 * is installed without SA_RESTART, so the system call fails with EINTR and the dispatcher returns IOStatus.INTERRUPTED.
 * On Windows the thread is identified by its id and CancelSynchronousIo ends the read it is blocked in; the dispatcher
 * sees ERROR_OPERATION_ABORTED and returns IOStatus.INTERRUPTED as well. */
#ifndef _WIN32
static int jcl_interrupt_signal = 0;
static void jcl_interrupt_handler(int sig) { (void)sig; }
#endif
BIND(void, jcl_NativeThread_init, "sun.nio.ch.NativeThread_init", (void)) {
#ifndef _WIN32
#ifdef SIGRTMAX
    jcl_interrupt_signal = SIGRTMAX - 2;
#else
    jcl_interrupt_signal = SIGUSR2;
#endif
    struct sigaction sa;
    memset(&sa, 0, sizeof sa);
    sa.sa_handler = jcl_interrupt_handler;
    sigemptyset(&sa.sa_mask);
    sigaction(jcl_interrupt_signal, &sa, NULL);
#endif
}
BIND(uint8_t, jcl_NativeThread_supportPendingSignals0, "sun.nio.ch.NativeThread_supportPendingSignals0", (void)) {
    return 0;
}
BIND(int64_t, jcl_NativeThread_current0, "sun.nio.ch.NativeThread_current0", (void)) {
#ifndef _WIN32
    return (int64_t)(intptr_t)pthread_self();
#else
    return (int64_t)GetCurrentThreadId();
#endif
}
BIND(void, jcl_NativeThread_signal0, "sun.nio.ch.NativeThread_signal0", (int64_t thread)) {
#ifndef _WIN32
    if (thread != 0 && thread != -1 && jcl_interrupt_signal != 0) pthread_kill((pthread_t)(intptr_t)thread, jcl_interrupt_signal);
#else
    if (thread != 0 && thread != -1) {
        HANDLE h = OpenThread(THREAD_TERMINATE, FALSE, (DWORD)thread);
        if (h != NULL) {
            CancelSynchronousIo(h);
            CloseHandle(h);
        }
    }
#endif
}
/* The JDK's NativeThread of Windows only returns 0 and signals nothing, and on Linux its class initializer installs
 * the signal handler: ThreadSupport calls these two instead (RuntimeCalls redirects NativeThread.current/signal). */
BIND(int64_t, jcl_ThreadSupport_threadId0, "io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime.ThreadSupport_threadId0", (void)) {
    static int installed = 0;
    if (!__atomic_load_n(&installed, __ATOMIC_ACQUIRE)) { /* idempotent: two threads may both install it */
        jcl_NativeThread_init();
        __atomic_store_n(&installed, 1, __ATOMIC_RELEASE);
    }
    return jcl_NativeThread_current0();
}
BIND(void, jcl_ThreadSupport_signalThread0, "io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime.ThreadSupport_signalThread0", (int64_t thread)) {
    jcl_NativeThread_signal0(thread);
}
BIND(void, jcl_IOUtil_initIDs, "sun.nio.ch.IOUtil_initIDs", (void)) {
}
BIND(int32_t, jcl_IOUtil_fdVal, "sun.nio.ch.IOUtil_fdVal", (void *fdObj)) {
    return jcl_chan_fd(fdObj);
}
BIND(void, jcl_IOUtil_setfdVal, "sun.nio.ch.IOUtil_setfdVal", (void *fdObj, int32_t fd)) {
    int32_t *slot = jcl_fd_slot(fdObj);
    if (slot != NULL) *slot = fd;
}
BIND(void, jcl_IOUtil_configureBlocking, "sun.nio.ch.IOUtil_configureBlocking", (void *fdObj, uint8_t blocking)) {
    int fd = jcl_chan_fd(fdObj);
    if (fd < 0) return;
#ifndef _WIN32
    int flags = fcntl(fd, F_GETFL, 0);
    if (flags >= 0) fcntl(fd, F_SETFL, blocking ? (flags & ~O_NONBLOCK) : (flags | O_NONBLOCK));
#else
    /* Only sockets are configured on Windows (a file channel is always blocking). */
    u_long mode = blocking ? 0 : 1;
    ioctlsocket((SOCKET)fd, FIONBIO, &mode);
#endif
}
BIND(int32_t, jcl_IOUtil_fdLimit, "sun.nio.ch.IOUtil_fdLimit", (void)) {
    return 1024;
}
BIND(uint8_t, jcl_IOUtil_randomBytes, "sun.nio.ch.IOUtil_randomBytes", (void *bytes)) {
    (void)bytes;
    return 0;
}
BIND(int32_t, jcl_IOUtil_iovMax, "sun.nio.ch.IOUtil_iovMax", (void)) { return 16; }
BIND(int64_t, jcl_IOUtil_writevMax, "sun.nio.ch.IOUtil_writevMax", (void)) { return 0x7fffffffLL; }

/* --- NativeProcess: child processes ------------------------------------- */

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

#ifndef _WIN32
/* Gives up the GIL around a call that blocks; only when threads have been started (before that nothing is locked). */
static int jcl_blocking_begin(void) {
    if (!nabu_threads_active) return 0;
    nabu_gil_release();
    return 1;
}
static void jcl_blocking_end(int released) {
    if (released) nabu_gil_acquire();
}
#endif

/* One process is started at a time. A child inherits every descriptor that is inheritable at that moment, so a
 * pipe of a spawn that is still busy in another thread would end up in this child, and the reader of that pipe
 * would never see the end of the stream. The lock is taken without blocking the thread for good: where the threads
 * take turns (NABU_GIL=1) a thread that waits for it has to hand over the turn. */
static pthread_mutex_t jcl_spawn_lock = PTHREAD_MUTEX_INITIALIZER;
static __thread char jcl_spawn_error[352];
static void jcl_spawn_fail(const char *reason) { snprintf(jcl_spawn_error, sizeof jcl_spawn_error, "%s", reason); }
static int64_t jcl_spawn_unlocked(void *cmd, void *env, void *dir, void *modesArr, void *fdsArr);

BIND(int64_t, jcl_NativeProcess_spawn0, "io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime.NativeProcess_spawn0",
     (void *cmd, void *env, void *dir, void *modesArr, void *fdsArr)) {
    while (pthread_mutex_trylock(&jcl_spawn_lock) != 0) nabu_gil_yield();
    jcl_spawn_error[0] = '\0';
    const int64_t result = jcl_spawn_unlocked(cmd, env, dir, modesArr, fdsArr);
    pthread_mutex_unlock(&jcl_spawn_lock);
    /* thrown after the unlock: a throw does not run code in the frames it leaves */
    if (jcl_spawn_error[0] != '\0') nabu_throw(jcl_throw("java/io/IOException", "java/lang/Exception", jcl_dup(jcl_spawn_error)));
    return result;
}

static int64_t jcl_spawn_unlocked(void *cmd, void *env, void *dir, void *modesArr, void *fdsArr) {
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
        jcl_spawn_fail(reason);
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
    jcl_spawn_fail("Cannot create a pipe");
    return 0;
#else
    /* The arguments come joined by \x01 (NativeProcess.commandLine). */
    const int32_t *m = (const int32_t *)((const char *)modesArr + 8);
    char *line = jcl_dup(nabu_cstr(cmd));
    int argc = 1;
    for (char *q = line; *q != '\0'; q++) if (*q == '\x01') argc++;
    char **argv = (char **)calloc((size_t)argc + 1, sizeof(char *));
    int at = 0;
    argv[at++] = line;
    for (char *q = line; *q != '\0'; q++) {
        if (*q == '\x01') { *q = '\0'; argv[at++] = q + 1; }
    }
    char **envp = NULL;
    if (env != NULL) {
        int32_t n = jcl_arr_len(env);
        envp = (char **)calloc((size_t)n + 1, sizeof(char *));
        for (int32_t i = 0; i < n; i++) envp[i] = jcl_dup(nabu_cstr(((void **)((char *)env + 8))[i]));
    }
    const char *workdir = dir != NULL ? nabu_cstr(dir) : NULL;
    int in[2] = {-1, -1}, out[2] = {-1, -1}, err[2] = {-1, -1}, ep[2] = {-1, -1};
    signal(SIGPIPE, SIG_IGN); /* a write to a child that has ended is an IOException, as on the JVM */
    if ((m[0] == 0 && pipe(in) != 0) || (m[1] == 0 && pipe(out) != 0)
            || (m[3] == 0 && m[2] == 0 && pipe(err) != 0) || pipe(ep) != 0) {
        int fdsToClose[8] = {in[0], in[1], out[0], out[1], err[0], err[1], ep[0], ep[1]};
        for (int i = 0; i < 8; i++) if (fdsToClose[i] >= 0) close(fdsToClose[i]);
        jcl_spawn_fail("Cannot create a pipe");
        return 0;
    }
    fcntl(ep[1], F_SETFD, FD_CLOEXEC);
    int released = jcl_blocking_begin();
    pid_t pid = fork();
    if (pid == 0) {
        /* the child: only calls that are safe between fork and exec */
        if (m[0] == 0) { dup2(in[0], 0); close(in[0]); close(in[1]); }
        if (m[1] == 0) { dup2(out[1], 1); close(out[0]); close(out[1]); }
        if (m[3] != 0) dup2(1, 2);
        else if (m[2] == 0) { dup2(err[1], 2); close(err[0]); close(err[1]); }
        close(ep[0]);
        if (workdir != NULL && chdir(workdir) != 0) {
            int e = errno;
            if (write(ep[1], &e, sizeof e) < 0) { /* nothing more to do */ }
            _exit(127);
        }
        extern char **environ;
        if (envp != NULL) environ = envp;
        execvp(argv[0], argv);
        int e = errno;
        if (write(ep[1], &e, sizeof e) < 0) { /* nothing more to do */ }
        _exit(127);
    }
    close(ep[1]);
    int exec_errno = 0;
    ssize_t got = 0;
    if (pid > 0) {
        do { got = read(ep[0], &exec_errno, sizeof exec_errno); } while (got < 0 && errno == EINTR);
        jcl_blocking_end(released);
    } else {
        jcl_blocking_end(released);
        exec_errno = errno;
        got = (ssize_t)sizeof exec_errno;
    }
    close(ep[0]);
    if (got == (ssize_t)sizeof exec_errno) {
        if (pid > 0) { int st; waitpid(pid, &st, 0); }
        if (in[0] >= 0) close(in[0]);
        if (in[1] >= 0) close(in[1]);
        if (out[0] >= 0) close(out[0]);
        if (out[1] >= 0) close(out[1]);
        if (err[0] >= 0) close(err[0]);
        if (err[1] >= 0) close(err[1]);
        char reason[320];
        snprintf(reason, sizeof reason, "Cannot run program \"%s\": error=%d, %s", argv[0], exec_errno,
                strerror(exec_errno));
        jcl_spawn_fail(reason);
        return 0;
    }
    /* The child has its ends; the parent keeps the others, which no other child may inherit. */
    if (in[0] >= 0) close(in[0]);
    if (out[1] >= 0) close(out[1]);
    if (err[1] >= 0) close(err[1]);
    if (in[1] >= 0) fcntl(in[1], F_SETFD, FD_CLOEXEC);
    if (out[0] >= 0) fcntl(out[0], F_SETFD, FD_CLOEXEC);
    if (err[0] >= 0) fcntl(err[0], F_SETFD, FD_CLOEXEC);
    fds[0] = in[1];
    fds[1] = out[0];
    fds[2] = err[0];
    return (int64_t)pid;
#endif
}

#ifndef _WIN32
/* The children that have been waited for: a process can only be reaped once, wait0 and exit0 both need the status.
 * Threads run in parallel: the table and the reaping are guarded by one lock, and a thread that waits for a child
 * does so without reaping it (waitid with WNOWAIT), so two threads can wait for the same child. */
static int64_t jcl_now_ms(void);
typedef struct { int32_t pid; int status; } jcl_child_status;
static jcl_child_status jcl_children[256];
static int jcl_child_next = 0;
static pthread_mutex_t jcl_children_lock = PTHREAD_MUTEX_INITIALIZER;

/* 1 when the child has ended and its status is kept; reaps it when it has ended and nobody did yet. */
static int jcl_child_collect(pid_t pid) {
    int ended = 0;
    pthread_mutex_lock(&jcl_children_lock);
    for (int i = 0; i < 256 && !ended; i++) if (jcl_children[i].pid == pid) ended = 1;
    if (!ended) {
        int status = 0;
        pid_t r;
        do { r = waitpid(pid, &status, WNOHANG); } while (r < 0 && errno == EINTR);
        if (r != 0) {
            jcl_children[jcl_child_next].pid = (int32_t)pid;
            jcl_children[jcl_child_next].status = r < 0 ? 0 : status;
            jcl_child_next = (jcl_child_next + 1) % 256;
            ended = 1;
        }
    }
    pthread_mutex_unlock(&jcl_children_lock);
    return ended;
}

/* Waits until the child has ended, without reaping it. */
static void jcl_child_await(pid_t pid) {
    siginfo_t info;
    int released = jcl_blocking_begin();
    while (waitid(P_PID, (id_t)pid, &info, WEXITED | WNOWAIT) < 0 && errno == EINTR) { }
    jcl_blocking_end(released);
}

/* Waits for the child at most millis milliseconds; 1 when it has ended. */
static int jcl_child_await_timed(pid_t pid, int64_t millis) {
#if defined(__linux__)
    /* A process file descriptor becomes readable when the process ends: a real wait with a timeout. */
    long fd = syscall(SYS_pidfd_open, pid, 0);
    if (fd >= 0) {
        struct pollfd pfd = { (int)fd, POLLIN, 0 };
        const int64_t until = jcl_now_ms() + millis;
        int released = jcl_blocking_begin();
        for (;;) {
            int64_t left = until - jcl_now_ms();
            if (left < 0) left = 0;
            int r = poll(&pfd, 1, (int)(left > 2000000000 ? 2000000000 : left));
            if (r >= 0 || errno != EINTR) break;
        }
        jcl_blocking_end(released);
        close((int)fd);
        return jcl_child_collect(pid);
    }
#endif
    /* No process descriptors (macOS, an old kernel): look now and then, for a little longer each time. */
    const int64_t until = jcl_now_ms() + millis;
    int64_t pause = 1;
    for (;;) {
        if (jcl_child_collect(pid)) return 1;
        int64_t left = until - jcl_now_ms();
        if (left <= 0) return 0;
        int released = jcl_blocking_begin();
        jcl_sleep_ms(pause < left ? pause : left);
        jcl_blocking_end(released);
        if (pause < 20) pause *= 2;
    }
}

/* 1 when the child has ended (its status is kept), 0 when it still runs. block: wait for it. */
static int jcl_child_reap(int64_t handle, int block) {
    pid_t pid = (pid_t)handle;
    if (block) jcl_child_await(pid);
    return jcl_child_collect(pid);
}
#endif

/* true when the process has ended (within millis; negative: wait without limit) */
BIND(uint8_t, jcl_NativeProcess_wait0, "io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime.NativeProcess_wait0", (int64_t handle, int64_t millis)) {
#ifdef _WIN32
    return WaitForSingleObject(jcl_process_handle(handle), millis < 0 ? INFINITE : (DWORD)millis) == WAIT_OBJECT_0;
#else
    if (millis < 0) return (uint8_t)jcl_child_reap(handle, 1);
    if (jcl_child_collect((pid_t)handle)) return 1;
    return (uint8_t)jcl_child_await_timed((pid_t)handle, millis);
#endif
}
BIND(int32_t, jcl_NativeProcess_exit0, "io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime.NativeProcess_exit0", (int64_t handle)) {
#ifdef _WIN32
    DWORD code = 0;
    if (!GetExitCodeProcess(jcl_process_handle(handle), &code)) return -1;
    return (int32_t)code;
#else
    if (!jcl_child_reap(handle, 0)) return -1;
    for (int i = 0; i < 256; i++) {
        if (jcl_children[i].pid == (int32_t)handle) {
            int st = jcl_children[i].status;
            return WIFEXITED(st) ? WEXITSTATUS(st) : 128 + WTERMSIG(st);
        }
    }
    return -1;
#endif
}
BIND(void, jcl_NativeProcess_destroy0, "io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime.NativeProcess_destroy0", (int64_t handle)) {
#ifdef _WIN32
    TerminateProcess(jcl_process_handle(handle), 1);
#else
    kill((pid_t)handle, SIGKILL);
#endif
}
BIND(int32_t, jcl_NativeProcess_pid0, "io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime.NativeProcess_pid0", (int64_t handle)) {
#ifdef _WIN32
    return (int32_t)GetProcessId(jcl_process_handle(handle));
#else
    return (int32_t)handle;
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
    int released = jcl_blocking_begin();
    ssize_t n;
    do { n = read(fd, (char *)b + 8 + off, (size_t)len); } while (n < 0 && errno == EINTR);
    jcl_blocking_end(released);
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
/* Once a java.util.Properties object stands for the system properties (System.getProperties or setProperties was
 * called), it is the one that counts: a change through that object is a change of the system properties. Before
 * that the table of the runtime is. */
static int jcl_props_compiled(void) {
    return jcl_compiled_props != NULL && nabu_properties_meta.get_property != NULL;
}
static void * jcl_java_lang_System_getProperty (void *key) {
    void *p = jcl_props_table();
    /* under the lock: System.setProperty may grow the table */
    nabu_rt_lock();
    void *v = jcl_map_get(p, key);
    nabu_rt_unlock();
    return v;
}
BIND(void *, jcl_java_lang_System_getProperty_jstr, "java.lang.System_getProperty", (void *key)) {
    if (jcl_props_compiled()) return nabu_properties_meta.get_property(jcl_compiled_props, key);
    void *r = (void *)jcl_java_lang_System_getProperty(key);
    return r == NULL ? NULL : nabu_jstring_new(nabu_cstr(r));
}
BIND(void *, jcl_java_lang_System_getProperty_P523beb0aP523beb0a_jstr, "java.lang.System_getProperty$P523beb0aP523beb0a", (void *key, void *dflt)) {
    if (jcl_props_compiled()) {
        void *v = nabu_properties_meta.get_property(jcl_compiled_props, key);
        return v != NULL ? v : dflt;
    }
    void *r = (void *)jcl_java_lang_System_getProperty(key);
    if (r == NULL) r = dflt;
    return r == NULL ? NULL : nabu_jstring_new(nabu_cstr(r));
}

static void jcl_throw_new(const char *cls, const char *super, const char *msg);

/* The key of System.setProperty and clearProperty: not null, not empty. */
static int jcl_property_key_ok(void *key) {
    if (key == NULL) {
        jcl_throw_new("java/lang/NullPointerException", "java/lang/RuntimeException", "key can't be null");
        return 0;
    }
    if (jcl_str(key)[0] == '\0') {
        jcl_throw_new("java/lang/IllegalArgumentException", "java/lang/RuntimeException", "key can't be empty");
        return 0;
    }
    return 1;
}

BIND(void *, jcl_java_lang_System_setProperty_jstr, "java.lang.System_setProperty", (void *key, void *value)) {
    if (!jcl_property_key_ok(key)) return NULL;
    if (value == NULL) {
        jcl_throw_new("java/lang/NullPointerException", "java/lang/RuntimeException", "value can't be null");
        return NULL;
    }
    void *table = jcl_props_table();
    char *k = jcl_dup(jcl_str(key));
    char *v = jcl_dup(jcl_str(value));
    nabu_rt_lock();
    const char *previous = (const char *)jcl_map_get(table, k);
    char *old = previous != NULL ? jcl_dup(previous) : NULL;
    jcl_map_put(table, k, v);
    void *result = NULL;
    if (jcl_props_compiled()) {
        result = nabu_properties_meta.set_property(jcl_compiled_props, nabu_jstring_new(k), nabu_jstring_new(v));
    } else if (old != NULL) {
        result = nabu_jstring_new(old);
    }
    nabu_rt_unlock();
    return result;
}

BIND(void *, jcl_java_lang_System_clearProperty_jstr, "java.lang.System_clearProperty", (void *key)) {
    if (!jcl_property_key_ok(key)) return NULL;
    void *table = jcl_props_table();
    nabu_rt_lock();
    const char *previous = (const char *)jcl_map_remove(table, key);
    void *result = NULL;
    if (jcl_props_compiled()) {
        result = nabu_properties_meta.remove(jcl_compiled_props, key);
    } else if (previous != NULL) {
        result = nabu_jstring_new(previous);
    }
    nabu_rt_unlock();
    return result;
}

/* System.setProperties: the given object stands for the system properties from now on; null brings back the
 * defaults (the table is made again, and the Properties object with it when it is asked for). */
BIND(void, jcl_java_lang_System_setProperties_jstr, "java.lang.System_setProperties", (void *props)) {
    jcl_props_table();
    nabu_rt_lock();
    if (props == NULL) {
        jcl_props = NULL;
        jcl_props_table_populate();
        jcl_compiled_props = NULL;
    } else if (nabu_properties_meta.get_property != NULL) {
        jcl_compiled_props = props;
    }
    nabu_rt_unlock();
}

/* ShutdownSupport.targetOf0(Thread): the Runnable a Thread object was made with, or null. */
BIND(void *, jcl_ShutdownSupport_targetOf0, "io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime.ShutdownSupport_targetOf0", (void *thread)) {
    s_thread *st = jcl_thread_state(thread);
    return st != NULL ? st->target : NULL;
}


/* The type info of the reflection objects the runtime makes (Method, Constructor, Field) comes from a cache without the
 * supers of the real classes: a cast of a Constructor to Member (Spring's BeanUtils & co) or of a Method to
 * Executable would fail. */
static void jcl_wire_reflect_type(void *obj, const char *type) {
    static const char *const executable_interfaces[] = {
        "java/lang/reflect/Member", "java/lang/reflect/AnnotatedElement", "java/lang/reflect/GenericDeclaration"
    };
    static const char *const field_interfaces[] = {
        "java/lang/reflect/Member", "java/lang/reflect/AnnotatedElement"
    };
    nabu_type_info *ti = (nabu_type_info *)((nabu_object *)obj)->type;
    if (ti == NULL || ti->interface_count != 0) return;
    char scratch[64];
    if (strcmp(type, "java/lang/reflect/Method") == 0 || strcmp(type, "java/lang/reflect/Constructor") == 0) {
        nabu_init_object_ex(scratch, "java/lang/reflect/Executable", "java/lang/reflect/AccessibleObject");
        nabu_init_object_ex(scratch, type, "java/lang/reflect/Executable");
        ti->interfaces = executable_interfaces;
        ti->interface_count = 3;
    } else if (strcmp(type, "java/lang/reflect/Field") == 0) {
        nabu_init_object_ex(scratch, type, "java/lang/reflect/AccessibleObject");
        ti->interfaces = field_interfaces;
        ti->interface_count = 2;
    }
}

static void *jcl_new_obj(const char *type, void *state) {
    void *obj = jcl_alloc(48 + 8);
    nabu_init_object(obj, type);
    if (strncmp(type, "java/lang/reflect/", 18) == 0) jcl_wire_reflect_type(obj, type);
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
/* Removes the entry (keeping the order of the others); returns its value or NULL. */
static void *jcl_map_remove(void *map, void *k) {
    s_map *m = (s_map *)jcl_state_kind(map, K_MAP);
    if (m == NULL) return NULL;
    for (int32_t i = 0; i < m->size; i++) {
        if (jcl_eq(m->ks[i], k)) {
            void *old = m->vs[i];
            for (int32_t j = i + 1; j < m->size; j++) { m->ks[j - 1] = m->ks[j]; m->vs[j - 1] = m->vs[j]; }
            m->size--;
            return old;
        }
    }
    return NULL;
}









static const char *jcl_desc_name(const char *desc) {
    if (desc == NULL) return "";
    if (desc[0] == 'L' && desc[strlen(desc) - 1] == ';') {
        /* A copy of its own: a buffer shared by the callers (and the threads) would be overwritten. */
        size_t n = strlen(desc) - 2;
        char *copy = (char *)jcl_alloc(n + 1);
        memcpy(copy, desc + 1, n);
        copy[n] = 0;
        return copy;
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

/* AnnotationSupport.blank0(Class): an annotation object of that type with no values (Method.getDefaultValue). */
BIND(void *, jcl_AnnotationSupport_blank0, "io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime.AnnotationSupport_blank0", (void *cls)) {
    nabu_annotation_entry *e = (nabu_annotation_entry *)jcl_alloc(sizeof *e);
    e->type_name = jcl_dup(jcl_str(cls));
    return jcl_ann_obj(e);
}

/* Annotation.equals/hashCode/toString: every getAnnotation builds a new object, so the same annotation is
 * equal by type and values (TypeDescriptor.annotationsMatch compares annotations of the same parameter). */
static int jcl_ann_values_equal(const nabu_annotation_value *a, const nabu_annotation_value *b) {
    if ((a->name == NULL) != (b->name == NULL)) return 0;
    if (a->name != NULL && strcmp(a->name, b->name) != 0) return 0;
    if (a->is_string != b->is_string) return 0;
    if ((a->text == NULL) != (b->text == NULL)) return 0;
    if (a->text != NULL && strcmp(a->text, b->text) != 0) return 0;
    return a->num_value == b->num_value;
}
BIND(int, jcl_java_lang_Annotation_equals, "java.lang.annotation.Annotation_equals", (void *self, void *other)) {
    if (self == other) return 1;
    if (self == NULL || other == NULL) return 0;
    s_ann *a = (s_ann *)jcl_state_kind(self, K_ANN);
    s_ann *b = (s_ann *)jcl_state_kind(other, K_ANN);
    if (a == NULL || b == NULL || a->e == NULL || b->e == NULL) return 0;
    if (a->e == b->e) return 1;
    if (strcmp(a->e->type_name, b->e->type_name) != 0) return 0;
    if (a->e->value_count != b->e->value_count) return 0;
    for (int i = 0; i < a->e->value_count; i++) {
        if (!jcl_ann_values_equal(&a->e->values[i], &b->e->values[i])) return 0;
    }
    return 1;
}
BIND(int32_t, jcl_java_lang_Annotation_hashCode, "java.lang.annotation.Annotation_hashCode", (void *self)) {
    s_ann *a = (s_ann *)jcl_state_kind(self, K_ANN);
    if (a == NULL || a->e == NULL) return 0;
    uint32_t h = 17;
    for (const char *p = a->e->type_name; *p != 0; p++) h = h * 31u + (unsigned char)*p;
    for (int i = 0; i < a->e->value_count; i++) {
        const char *t = a->e->values[i].text;
        if (t != NULL) for (; *t != 0; t++) h = h * 31u + (unsigned char)*t;
        h = h * 31u + (uint32_t)(int64_t)a->e->values[i].num_value;
    }
    return (int32_t)h;
}
BIND(void *, jcl_java_lang_Annotation_toString, "java.lang.annotation.Annotation_toString", (void *self)) {
    s_ann *a = (s_ann *)jcl_state_kind(self, K_ANN);
    char buf[1024];
    if (a == NULL || a->e == NULL) return nabu_jstring_new("@?");
    int n = snprintf(buf, sizeof buf, "@");
    for (const char *p = a->e->type_name; *p != 0 && n < 900; p++) buf[n++] = (*p == '/') ? '.' : *p;
    buf[n++] = '(';
    for (int i = 0; i < a->e->value_count && n < 900; i++) {
        const nabu_annotation_value *v = &a->e->values[i];
        if (i > 0) n += snprintf(buf + n, sizeof buf - n, ", ");
        if (v->name != NULL) n += snprintf(buf + n, sizeof buf - n, "%s=", v->name);
        if (v->text != NULL) n += snprintf(buf + n, sizeof buf - n, "%s", v->text);
        else n += snprintf(buf + n, sizeof buf - n, "%g", v->num_value);
    }
    if (n > 1000) n = 1000;
    snprintf(buf + n, sizeof buf - n, ")");
    return nabu_jstring_new(buf);
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
    (void)loader;
    if (init) {
        /* Initialization on request, as the JVM does: frameworks rely on the static initializer running inside
         * the call (CGLIB's generated classes read the generator that is current while they are loaded). */
        const char *n = jcl_str(name);
        char internal[512];
        size_t len = strlen(n);
        if (len > 0 && len < sizeof internal) {
            for (size_t i = 0; i <= len; i++) internal[i] = n[i] == '.' ? '/' : n[i];
            const nabu_reflection_info *ri = nabu_lookup_reflection(internal);
            if (ri != NULL && ri->class_init != NULL) ri->class_init();
        }
    }
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
void *jcl_java_lang_Class_getComponentType(void *self) __asm__(JCL_SYM("java.lang.Class_getComponentType"));
/* Class.componentType (Java 12): the same. */
BIND(void *, jcl_java_lang_Class_componentType, "java.lang.Class_componentType", (void *self)) {
    return jcl_java_lang_Class_getComponentType(self);
}
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
/* Class.arrayType (Java 12): the class of an array of this class ("[Ljava/lang/String;", "[I", "[[I"). */
BIND(void *, jcl_java_lang_Class_arrayType, "java.lang.Class_arrayType", (void *self)) {
    const char *n = jcl_str(self);
    static const struct { const char *name; const char *code; } prims[] = {
        {"int", "[I"}, {"long", "[J"}, {"boolean", "[Z"}, {"double", "[D"}, {"float", "[F"},
        {"short", "[S"}, {"byte", "[B"}, {"char", "[C"}
    };
    if (n[0] == '[') return jcl_class(jcl_concat("[", n));
    for (size_t i = 0; i < sizeof prims / sizeof prims[0]; i++) {
        if (strcmp(n, prims[i].name) == 0) return jcl_class(prims[i].code);
    }
    return jcl_class(jcl_concat(jcl_concat("[L", n), ";"));
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
void *jcl_java_lang_Class_getSuperclass(void *self) __asm__(JCL_SYM("java.lang.Class_getSuperclass"));

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
static int32_t jcl_class_flags(void *self);
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
    {
        /* An interface has no superclass (null, not Object): Spring's ReflectionUtils.doWithMethods then goes on with
         * the super interfaces. */
        const int32_t flags = jcl_class_flags(self);
        if (flags >= 0 && (flags & 0x200) != 0) return NULL;
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
/* Class.getInterfaces: the interfaces the compiled type records (they include the known superinterfaces). */
BIND(void *, jcl_java_lang_Class_getInterfaces, "java.lang.Class_getInterfaces", (void *self)) {
    const nabu_type_info *ti = nabu_compiled_type_for(jcl_str(self));
    int32_t count = ti != NULL ? ti->interface_count : 0;
    void *arr = jcl_arr_typed(count, "[Ljava/lang/Class;");
    for (int32_t i = 0; i < count; i++) jcl_arr_set(arr, i, jcl_class(ti->interfaces[i]));
    return arr;
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
/* The element class name of an array class name ("[Ljava/lang/String;" or "[pkg/Cls" or "[[I"); *primitive is set
 * for an array of a primitive type. */
static void jcl_array_element_name(const char *array, char *out, size_t size, int *primitive) {
    const char *p = array + 1;
    size_t len = strlen(p);
    *primitive = 0;
    if (*p == 'L' && len > 2 && p[len - 1] == ';') {
        p++;
        len -= 2;
    } else if (len == 1 && strchr("ZBCSIJFD", *p) != NULL) {
        *primitive = 1;
    }
    if (len >= size) len = size - 1;
    memcpy(out, p, len);
    out[len] = 0;
}
BIND(int, jcl_java_lang_Class_isAssignableFrom, "java.lang.Class_isAssignableFrom", (void *self, void *o)) {
    const char *target = jcl_str(self), *from = jcl_str(o);
    if (strcmp(target, from) == 0) return 1;
    if (from[0] == '[') {
        /* An array is an Object, Cloneable and Serializable; arrays of references are covariant. */
        if (strcmp(target, "java/lang/Object") == 0 || strcmp(target, "java/lang/Cloneable") == 0
                || strcmp(target, "java/io/Serializable") == 0) return 1;
        if (target[0] != '[') return 0;
        char a[256], b[256];
        int pa, pb;
        jcl_array_element_name(target, a, sizeof a, &pa);
        jcl_array_element_name(from, b, sizeof b, &pb);
        if (pa || pb) return 0;
        return jcl_java_lang_Class_isAssignableFrom(jcl_class(a), jcl_class(b));
    }
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
/* The class-file flags of a class of the program (nabu_class_flags, written by the backend; 0x10000 = record),
 * or -1 for a class it does not list (a primitive, an array, a class without compiled code). */
typedef struct { const char *name; int32_t flags; } nabu_class_flag;
extern const nabu_class_flag nabu_class_flags[];
extern const int32_t nabu_class_flag_count;
static const nabu_class_flag **jcl_flags_sorted = NULL;
static int jcl_flag_cmp(const void *a, const void *b) {
    return strcmp((*(const nabu_class_flag *const *)a)->name, (*(const nabu_class_flag *const *)b)->name);
}
static int32_t jcl_class_flags(void *self) {
    if (jcl_flags_sorted == NULL) {
        nabu_rt_lock();
        if (jcl_flags_sorted == NULL) {
            const nabu_class_flag **sorted = (const nabu_class_flag **)malloc(sizeof *sorted * (size_t)(nabu_class_flag_count + 1));
            for (int32_t i = 0; i < nabu_class_flag_count; i++) sorted[i] = &nabu_class_flags[i];
            qsort(sorted, (size_t)nabu_class_flag_count, sizeof *sorted, jcl_flag_cmp);
            jcl_flags_sorted = sorted;
        }
        nabu_rt_unlock();
    }
    nabu_class_flag key = { jcl_str(self), 0 };
    const nabu_class_flag *k = &key;
    const nabu_class_flag **hit = (const nabu_class_flag **)bsearch(&k, jcl_flags_sorted,
            (size_t)nabu_class_flag_count, sizeof *jcl_flags_sorted, jcl_flag_cmp);
    return hit != NULL ? (*hit)->flags : -1;
}
BIND(int, jcl_java_lang_Class_isInterface, "java.lang.Class_isInterface", (void *self)) {
    int32_t f = jcl_class_flags(self);
    return f >= 0 && (f & 0x200) != 0;
}
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
/* One class loader: loadClass is Class.forName (ClassNotFoundException for a class the program does not have). */
/* ClassLoader(String, ClassLoader): a native loader has no JDK state to set up. */
BIND(void, jcl_java_lang_ClassLoader_init, "java.lang.ClassLoader_init", (void *self)) { (void)self; }
/* The one class loader of a native program (LoaderSupport.NativeClassLoader) has no parent and no packages of its own. */
BIND(void *, jcl_java_lang_ClassLoader_getParent, "java.lang.ClassLoader_getParent", (void *self)) { (void)self; return NULL; }
BIND(void, jcl_java_lang_ClassLoader_init_parent, "java.lang.ClassLoader_init$P", (void *self, void *parent)) { (void)self; (void)parent; }
BIND(void *, jcl_java_lang_ClassLoader_getUnnamedModule, "java.lang.ClassLoader_getUnnamedModule", (void *self)) { (void)self; return NULL; }
BIND(void *, jcl_java_lang_ClassLoader_findResource, "java.lang.ClassLoader_findResource", (void *self, void *name)) { (void)self; (void)name; return NULL; }
BIND(void *, jcl_java_lang_ClassLoader_definePackage, "java.lang.ClassLoader_definePackage", (void *self, void *name, void *spec)) { (void)self; (void)name; (void)spec; return NULL; }
BIND(void *, jcl_java_lang_ClassLoader_getName, "java.lang.ClassLoader_getName", (void *self)) { (void)self; return nabu_jstring_new("app"); }
BIND(int, jcl_java_lang_ClassLoader_equals, "java.lang.ClassLoader_equals", (void *self, void *other)) { return self == other; }
BIND(int32_t, jcl_java_lang_ClassLoader_hashCode, "java.lang.ClassLoader_hashCode", (void *self)) { return (int32_t)((intptr_t)self >> 4); }
BIND(int, jcl_java_lang_ClassLoader_isRegisteredAsParallelCapable, "java.lang.ClassLoader_isRegisteredAsParallelCapable", (void *self)) { (void)self; return 0; }
BIND(int, jcl_java_lang_ClassLoader_registerAsParallelCapable, "java.lang.ClassLoader_registerAsParallelCapable", (void)) { return 1; }
BIND(void *, jcl_java_lang_ClassLoader_getDefinedPackage, "java.lang.ClassLoader_getDefinedPackage", (void *self, void *name)) { (void)self; (void)name; return NULL; }
BIND(void *, jcl_java_lang_ClassLoader_getDefinedPackages, "java.lang.ClassLoader_getDefinedPackages", (void *self)) { (void)self; return jcl_arr_typed(0, "[Ljava/lang/Package;"); }
BIND(void, jcl_java_lang_ClassLoader_clearAssertionStatus, "java.lang.ClassLoader_clearAssertionStatus", (void *self)) { (void)self; }
BIND(void, jcl_java_lang_ClassLoader_setDefaultAssertionStatus, "java.lang.ClassLoader_setDefaultAssertionStatus", (void *self, int enabled)) { (void)self; (void)enabled; }
BIND(void, jcl_java_lang_ClassLoader_init_named, "java.lang.ClassLoader_init$PP523beb0aP2f7ad930", (void *self, void *name, void *parent)) { (void)self; (void)name; (void)parent; }
BIND(void *, jcl_java_lang_ClassLoader_loadClass, "java.lang.ClassLoader_loadClass", (void *self, void *name)) {
    (void)self;
    return jcl_java_lang_Class_forName(name);
}
BIND(void *, jcl_java_lang_ClassLoader_loadClass_resolve, "java.lang.ClassLoader_loadClass$P523beb0aZ", (void *self, void *name, uint8_t resolve)) {
    (void)self; (void)resolve;
    return jcl_java_lang_Class_forName(name);
}
/* SharedSecrets' access to java.util.ImmutableCollections (Stream.toList): the class that implements the access
 * interface is never initialized here, so the interface call has no implementation. The lists come from the
 * compiled Arrays.asList / ImmutableCollections.listFromTrustedArray. */
/* Weak default definition (a weak undefined reference does not link on PE/COFF: the REL32 is truncated). */
__attribute__((weak)) void *jcl_java_util_Arrays_asList(void *array) __asm__(JCL_SYM("java.util.Arrays" "_asList"));
__attribute__((weak)) void *jcl_java_util_Arrays_asList(void *array) { (void)array; return NULL; }
BIND(void *, jcl_JavaUtilCollectionAccess_listFromTrustedArrayNullsAllowed,
     "jdk.internal.access.JavaUtilCollectionAccess_listFromTrustedArrayNullsAllowed", (void *self, void *array)) {
    (void)self;
    return jcl_java_util_Arrays_asList(array);
}
BIND(void *, jcl_JavaUtilCollectionAccess_listFromTrustedArray,
     "jdk.internal.access.JavaUtilCollectionAccess_listFromTrustedArray", (void *self, void *array)) {
    (void)self;
    return jcl_java_util_Arrays_asList(array);
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
    } else if (strcmp(cls, "java/lang/Class") == 0 || strcmp(cls, "java/lang/reflect/Constructor") == 0) {
        /* The generic classes the runtime provides itself have no reflection data: Class<T> and Constructor<T>.
         * Without their type variable java.beans' TypeResolver finds Class<X> malformed (0 formal arguments). */
        jcl_parse_formals("<T:Ljava/lang/Object;>Ljava/lang/Object;", scope, variables, count);
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

/* Class.getGenericSuperclass / getGenericInterfaces: from the class signature "<formals>Lsuper;Liface;..."
 * when the class has one, else the erased ones. */
static const char *jcl_class_signature_rest(const char *cls, jcl_type_scope *scope) {
    const nabu_reflection_info *ri = nabu_lookup_reflection(cls);
    if (ri == NULL || ri->signature == NULL) return NULL;
    void *ignored[16];
    int32_t count;
    return jcl_parse_formals(ri->signature, scope, ignored, &count);
}
BIND(void *, jcl_java_lang_Class_getGenericSuperclass, "java.lang.Class_getGenericSuperclass", (void *self)) {
    jcl_type_scope scope = {{0}, {0}, 0};
    const char *p = jcl_class_signature_rest(jcl_str(self), &scope);
    if (p == NULL || *p != 'L') return jcl_java_lang_Class_getSuperclass(self);
    return jcl_parse_type(&p, &scope).type;
}
BIND(void *, jcl_java_lang_Class_getGenericInterfaces, "java.lang.Class_getGenericInterfaces", (void *self)) {
    jcl_type_scope scope = {{0}, {0}, 0};
    const char *p = jcl_class_signature_rest(jcl_str(self), &scope);
    if (p == NULL) return jcl_java_lang_Class_getInterfaces(self);
    if (*p == 'L') jcl_parse_type(&p, &scope);   /* the superclass */
    void *types[64];
    int32_t count = 0;
    while (*p == 'L' && count < 64) types[count++] = jcl_parse_type(&p, &scope).type;
    void *arr = jcl_arr_ref(count);
    jcl_arr_set_type(arr, "[Ljava/lang/reflect/Type;");
    for (int32_t i = 0; i < count; i++) jcl_arr_set(arr, i, types[i]);
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
/* Declared annotations of whatever an AnnotatedElement call arrives on: a Field, a Method/Constructor or a Class. */
static void *jcl_element_annotations(void *self) {
    s_field *f = jcl_field_of(self);
    if (f != NULL) return jcl_annotations_for(f->cls, f->f->name);
    s_method *m = jcl_method_of(self);
    if (m != NULL) return jcl_annotations_for(m->cls, jcl_exec_annotation_owner(m));
    const nabu_reflection_info *ri = nabu_lookup_reflection(jcl_str(self));
    int32_t count = 0;
    for (int i = 0; ri != NULL && i < ri->annotation_count; i++) if (ri->annotations[i].owner == NULL) count++;
    void *arr = jcl_arr_ref(count);
    jcl_arr_set_type(arr, "[Ljava/lang/annotation/Annotation;");
    int32_t k = 0;
    for (int i = 0; ri != NULL && i < ri->annotation_count; i++) {
        if (ri->annotations[i].owner == NULL) jcl_arr_set(arr, k++, jcl_ann_obj(&ri->annotations[i]));
    }
    return arr;
}
BIND(void *, jcl_java_lang_Class_getDeclaredAnnotations, "java.lang.Class_getDeclaredAnnotations", (void *self)) { return jcl_element_annotations(self); }
BIND(void *, jcl_java_lang_Class_getAnnotations, "java.lang.Class_getAnnotations", (void *self)) { return jcl_element_annotations(self); }
BIND(void *, jcl_java_lang_Class_getDeclaredAnnotation, "java.lang.Class_getDeclaredAnnotation", (void *self, void *ann)) { return jcl_java_lang_Class_getAnnotation(self, ann); }
BIND(int, jcl_java_lang_Class_isAnnotationPresent, "java.lang.Class_isAnnotationPresent", (void *self, void *ann)) { return jcl_java_lang_Class_getAnnotation(self, ann) != NULL; }
BIND(void *, jcl_java_lang_reflect_AnnotatedElement_getDeclaredAnnotations, "java.lang.reflect.AnnotatedElement_getDeclaredAnnotations", (void *self)) { return jcl_element_annotations(self); }
BIND(void *, jcl_java_lang_reflect_AnnotatedElement_getAnnotations, "java.lang.reflect.AnnotatedElement_getAnnotations", (void *self)) { return jcl_element_annotations(self); }
/* AnnotatedElement.getDeclaredAnnotation / isAnnotationPresent are default methods, compiled from the JDK. */
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
/* Field.equals/hashCode (a Field is made again for every lookup, so == does not do): same class and name. */
BIND(int, jcl_java_lang_reflect_Field_equals, "java.lang.reflect.Field_equals", (void *self, void *other)) {
    if (self == other) return 1;
    s_field *a = (s_field *)jcl_state_kind(self, K_FIELD);
    s_field *b = (s_field *)jcl_state_kind(other, K_FIELD);
    return a != NULL && b != NULL && a->f != NULL && b->f != NULL && strcmp(a->cls, b->cls) == 0
            && strcmp(a->f->name, b->f->name) == 0;
}
BIND(int, jcl_java_lang_reflect_AccessibleObject_equals, "java.lang.reflect.AccessibleObject_equals", (void *self, void *other)) {
    return self == other;
}
static int32_t jcl_string_hash(const char *s, int dots) {
    int32_t h = 0;
    for (; *s; s++) h = 31 * h + (dots && *s == '/' ? '.' : (unsigned char)*s);
    return h;
}
BIND(int32_t, jcl_java_lang_reflect_Field_hashCode, "java.lang.reflect.Field_hashCode", (void *self)) {
    s_field *f = (s_field *)jcl_state_kind(self, K_FIELD);
    if (f == NULL || f->f == NULL) return (int32_t)((intptr_t)self >> 4);
    return jcl_string_hash(f->cls, 1) ^ jcl_string_hash(f->f->name, 0);
}
BIND(int32_t, jcl_java_lang_reflect_AccessibleObject_hashCode, "java.lang.reflect.AccessibleObject_hashCode", (void *self)) {
    return (int32_t)((intptr_t)self >> 4);
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
/* Access checks do not exist here: everything is accessible. */
BIND(int, jcl_ctor_isAccessible, "java.lang.reflect.Constructor_isAccessible", (void *self)) { (void)self; return 1; }
BIND(int, jcl_field_isAccessible, "java.lang.reflect.Field_isAccessible", (void *self)) { (void)self; return 1; }
BIND(void, jcl_field_setAccessible, "java.lang.reflect.Field_setAccessible", (void *self, int v)) { (void)self; (void)v; }
BIND(int, jcl_field_trySetAccessible, "java.lang.reflect.Field_trySetAccessible", (void *self)) { (void)self; return 1; }
BIND(int, jcl_ao_canAccess, "java.lang.reflect.AccessibleObject_canAccess", (void *self, void *o)) { (void)self; (void)o; return 1; }
BIND(void, jcl_ao_setAccessible_array, "java.lang.reflect.AccessibleObject_setAccessible$P3ad1f50fZ", (void *array, int v)) { (void)array; (void)v; }
BIND(int, jcl_field_isSynthetic, "java.lang.reflect.Field_isSynthetic", (void *self)) { (void)self; return 0; }
BIND(int, jcl_field_isEnumConstant, "java.lang.reflect.Field_isEnumConstant", (void *self)) {
    return (jcl_java_lang_reflect_Field_getModifiers(self) & 0x4000) != 0;
}
BIND(int, jcl_exec_isSynthetic, "java.lang.reflect.Executable_isSynthetic", (void *self)) {
    return jcl_java_lang_reflect_Method_isSynthetic(self);
}
/* java.lang.reflect.Member through the interface: the object is a Field, a Method or a Constructor of the C runtime,
 * no compiled class implements Member (hibernate-validator's ReflectionHelper asks a Member for its declaring class). */
BIND(void *, jcl_member_getDeclaringClass, "java.lang.reflect.Member_getDeclaringClass", (void *self)) {
    if (jcl_field_of(self) != NULL) return jcl_java_lang_reflect_Field_getDeclaringClass(self);
    return jcl_java_lang_reflect_Constructor_getDeclaringClass(self);
}
BIND(void *, jcl_member_getName, "java.lang.reflect.Member_getName", (void *self)) {
    if (jcl_field_of(self) != NULL) return jcl_java_lang_reflect_Field_getName_jstr(self);
    return jcl_java_lang_reflect_Method_getName_jstr(self);
}
BIND(int32_t, jcl_member_getModifiers, "java.lang.reflect.Member_getModifiers", (void *self)) {
    if (jcl_field_of(self) != NULL) return jcl_java_lang_reflect_Field_getModifiers(self);
    return jcl_java_lang_reflect_Method_getModifiers(self);
}
BIND(int, jcl_member_isSynthetic, "java.lang.reflect.Member_isSynthetic", (void *self)) {
    if (jcl_field_of(self) != NULL) return 0;
    return jcl_java_lang_reflect_Method_isSynthetic(self);
}
BIND(void *, jcl_field_getAnnotation, "java.lang.reflect.Field_getAnnotation", (void *self, void *ann)) {
    return jcl_java_lang_reflect_AccessibleObject_getAnnotation(self, ann);
}
BIND(int, jcl_field_isAnnotationPresent, "java.lang.reflect.Field_isAnnotationPresent", (void *self, void *ann)) {
    return jcl_java_lang_reflect_AccessibleObject_getAnnotation(self, ann) != NULL;
}
BIND(int, jcl_exec_isAnnotationPresent, "java.lang.reflect.Executable_isAnnotationPresent", (void *self, void *ann)) {
    return jcl_java_lang_reflect_Method_isAnnotationPresent(self, ann);
}
JCL_ALIAS1(void *, jcl_exec_getName, "java.lang.reflect.Executable_getName", jcl_java_lang_reflect_Method_getName_jstr)
JCL_ALIAS1(void *, jcl_exec_getParameterTypes, "java.lang.reflect.Executable_getParameterTypes", jcl_java_lang_reflect_Method_getParameterTypes)
/* Package-private Executable methods the compiled Parameter and Executable code call. */
JCL_ALIAS1(void *, jcl_exec_getSharedParameterTypes, "java.lang.reflect.Executable_getSharedParameterTypes", jcl_java_lang_reflect_Method_getParameterTypes)
JCL_ALIAS1(void *, jcl_exec_getAllGenericParameterTypes, "java.lang.reflect.Executable_getAllGenericParameterTypes", jcl_java_lang_reflect_Method_getGenericParameterTypes)
JCL_ALIAS1(void *, jcl_exec_toGenericString, "java.lang.reflect.Executable_toGenericString", jcl_java_lang_reflect_Method_toGenericString_jstr)
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
/* Names of the thread groups, told by ThreadGroup's constructor; the groups stay reachable from ThreadSupport, so
 * the pointers stay valid. A thread without a group is in the main group; a thread that has ended has none in the JDK
 * but its group is kept here. */
static struct { void *group; char *name; } jcl_group_names[256];
static int jcl_group_name_count = 0;

static const char *jcl_group_name(void *group) {
    const char *found = "main";
    nabu_rt_lock();
    for (int i = 0; i < jcl_group_name_count; i++) {
        if (jcl_group_names[i].group == group) { found = jcl_group_names[i].name; break; }
    }
    nabu_rt_unlock();
    return found;
}

BIND(void, jcl_ThreadSupport_nameGroup0, "io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime.ThreadSupport_nameGroup0", (void *group, void *name)) {
    char *copy = jcl_dup(jcl_str(name));
    nabu_rt_lock();
    if (jcl_group_name_count < 256) {
        jcl_group_names[jcl_group_name_count].group = group;
        jcl_group_names[jcl_group_name_count].name = copy;
        jcl_group_name_count++;
    }
    nabu_rt_unlock();
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
    s_thread *thread = jcl_thread_state(self);
    if (thread != NULL) {
        char text[512];
        snprintf(text, sizeof text, "Thread[#%lld,%s,%d,%s]", (long long)thread->id,
                 thread->name != NULL ? thread->name : "", thread->priority, jcl_group_name(thread->group));
        return nabu_jstring_new(text);
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
BIND(int, jcl_java_lang_Class_isRecord0, "java.lang.Class_isRecord0", (void *self)) {
    int32_t f = jcl_class_flags(self);
    return f >= 0 && (f & 0x10000) != 0;
}
BIND(int64_t, jcl_java_lang_Double_doubleToRawLongBits, "java.lang.Double_doubleToRawLongBits", (double v)) { int64_t r; memcpy(&r, &v, 8); return r; }
BIND(double, jcl_java_lang_Double_longBitsToDouble, "java.lang.Double_longBitsToDouble", (int64_t v)) { double r; memcpy(&r, &v, 8); return r; }
BIND(int32_t, jcl_java_lang_Float_floatToRawIntBits, "java.lang.Float_floatToRawIntBits", (float v)) { int32_t r; memcpy(&r, &v, 4); return r; }
BIND(float, jcl_java_lang_Float_intBitsToFloat, "java.lang.Float_intBitsToFloat", (int32_t v)) { float r; memcpy(&r, &v, 4); return r; }
BIND(void *, jcl_java_lang_String_intern, "java.lang.String_intern", (void *self)) { return self; }
#ifdef __linux__
/* The processors a CPU quota allows: ceil(quota / period). `text` is the content of cgroup v2 cpu.max ("max 100000"
 * or "200000 100000"); for cgroup v1 the quota and the period are passed on their own. 0 when there is no limit. */
static int jcl_quota_processors(long long quota, long long period) {
    if (quota <= 0 || period <= 0) return 0;
    const long long n = (quota + period - 1) / period;
    return n < 1 ? 1 : (n > 100000 ? 100000 : (int)n);
}
static int jcl_cpu_max_processors(const char *text) {
    long long quota = 0, period = 0;
    if (strncmp(text, "max", 3) == 0) return 0;
    if (sscanf(text, "%lld %lld", &quota, &period) != 2) return 0;
    return jcl_quota_processors(quota, period);
}
static int jcl_read_small_file(const char *path, char *buffer, size_t size) {
    FILE *f = fopen(path, "r");
    if (f == NULL) return 0;
    const size_t n = fread(buffer, 1, size - 1, f);
    fclose(f);
    buffer[n] = '\0';
    return n > 0;
}
/* The CPU limit of the cgroup the process runs in (0 for none): cgroup v2 first, then v1. */
static int jcl_cgroup_processors(void) {
    char text[256], path[512], line[512];
    if (jcl_read_small_file("/sys/fs/cgroup/cpu.max", text, sizeof text)) {
        const int n = jcl_cpu_max_processors(text);
        if (n > 0) return n;
    }
    FILE *self = fopen("/proc/self/cgroup", "r");
    if (self != NULL) {
        while (fgets(line, sizeof line, self) != NULL) {
            if (strncmp(line, "0::/", 4) != 0) continue;
            line[strcspn(line, "\r\n")] = '\0';
            snprintf(path, sizeof path, "/sys/fs/cgroup/%s/cpu.max", line + 4);
            if (jcl_read_small_file(path, text, sizeof text)) {
                const int n = jcl_cpu_max_processors(text);
                if (n > 0) { fclose(self); return n; }
            }
        }
        fclose(self);
    }
    static const char *const v1_dirs[] = { "/sys/fs/cgroup/cpu", "/sys/fs/cgroup/cpu,cpuacct" };
    for (size_t i = 0; i < sizeof v1_dirs / sizeof v1_dirs[0]; i++) {
        char quota_text[64], period_text[64];
        snprintf(path, sizeof path, "%s/cpu.cfs_quota_us", v1_dirs[i]);
        if (!jcl_read_small_file(path, quota_text, sizeof quota_text)) continue;
        snprintf(path, sizeof path, "%s/cpu.cfs_period_us", v1_dirs[i]);
        if (!jcl_read_small_file(path, period_text, sizeof period_text)) continue;
        const int n = jcl_quota_processors(atoll(quota_text), atoll(period_text));
        if (n > 0) return n;
    }
    return 0;
}
#endif
/* The processors of the machine, or what NABU_CPUS says: the virtual thread scheduler and the pools size themselves
 * by it. */
#ifdef _WIN32
/* Windows 11 and Server 2022 (build 20348) and later let a process use the processors of all processor groups; before
 * that its threads stay in one group of at most 64 processors. */
static int jcl_windows_spans_groups(void) {
    typedef LONG (WINAPI *rtl_get_version_fn)(OSVERSIONINFOW *);
    HMODULE ntdll = GetModuleHandleW(L"ntdll.dll");
    rtl_get_version_fn get_version = ntdll == NULL ? NULL : (rtl_get_version_fn)(void *)GetProcAddress(ntdll, "RtlGetVersion");
    OSVERSIONINFOW version;
    memset(&version, 0, sizeof version);
    version.dwOSVersionInfoSize = sizeof version;
    return get_version != NULL && get_version(&version) == 0 && version.dwBuildNumber >= 20348;
}

/* The CPU rate a job object caps the process at (docker --cpus on Windows): a part of all processors of the machine,
 * in 1/100 of a percent. Returns the number of processors that comes to, or 0 for no cap. */
static int jcl_job_cpu_cap(int machine_processors) {
    struct { DWORD flags; DWORD rate; } info;  /* JOBOBJECT_CPU_RATE_CONTROL_INFORMATION: a rate, a weight or min/max */
    memset(&info, 0, sizeof info);
    if (!QueryInformationJobObject(NULL, (JOBOBJECTINFOCLASS) 15 /* JobObjectCpuRateControlInformation */, &info, sizeof info, NULL)) {
        return 0;
    }
    const DWORD enable = 0x1, hard_cap = 0x4, min_max = 0x10;
    if (!(info.flags & enable)) return 0;
    DWORD rate = 0;
    if (info.flags & hard_cap) rate = info.rate;
    else if (info.flags & min_max) rate = info.rate >> 16;  /* MaxRate is the upper word */
    if (rate == 0 || rate >= 10000) return 0;
    const long long n = ((long long) rate * machine_processors + 9999) / 10000;
    return n < 1 ? 1 : (int) n;
}
#endif
static int jcl_online_processors(void) {
#ifdef _WIN32
    SYSTEM_INFO info;
    GetSystemInfo(&info);
    int machine = (int)info.dwNumberOfProcessors;  /* the processors of the current group */
    int allowed = machine;
    /* the processors this process may run on (start /affinity, a job object), when that is less than the machine */
    DWORD_PTR process_mask = 0, system_mask = 0;
    if (GetProcessAffinityMask(GetCurrentProcess(), &process_mask, &system_mask) && process_mask != 0
            && process_mask != system_mask) {
        allowed = 0;
        for (DWORD_PTR bits = process_mask; bits != 0; bits &= bits - 1) allowed++;
    } else if (GetActiveProcessorGroupCount() > 1 && jcl_windows_spans_groups()) {
        const int all = (int)GetActiveProcessorCount(ALL_PROCESSOR_GROUPS);
        if (all > 0) allowed = all;
    }
    const int total = GetActiveProcessorGroupCount() > 1 ? (int)GetActiveProcessorCount(ALL_PROCESSOR_GROUPS) : machine;
    const int cap = jcl_job_cpu_cap(total > 0 ? total : machine);
    if (cap > 0 && cap < allowed) allowed = cap;
    return allowed;
#else
#ifdef __linux__
    /* the processors this process may run on (taskset, a cpuset of a container), not those of the machine, */
    int allowed = 0;
    cpu_set_t set;
    if (sched_getaffinity(0, sizeof(set), &set) == 0) allowed = CPU_COUNT(&set);
    /* ... and not more than the CPU quota of its cgroup (docker --cpus, a Kubernetes limit) */
    const int quota = jcl_cgroup_processors();
    if (quota > 0 && (allowed <= 0 || quota < allowed)) allowed = quota;
    if (allowed > 0) return allowed;
#endif
#ifdef _SC_NPROCESSORS_ONLN
    return (int)sysconf(_SC_NPROCESSORS_ONLN);
#else
    return 1;
#endif
#endif
}
BIND(int32_t, jcl_java_lang_Runtime_availableProcessors, "java.lang.Runtime_availableProcessors", (void *self)) {
    (void)self;
    static int cached = 0;
    if (cached > 0) return cached;
    const char *cpus = getenv("NABU_CPUS");
    const int n = cpus != NULL ? atoi(cpus) : jcl_online_processors();
    return cached = (n > 0 ? n : 1);
}
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

/* StackTraceSupport.frameInlines0(long address): the functions at the address when LLVM inlined some, innermost first,
 * one line each "symbol<TAB>file<TAB>line<NEWLINE>" (file empty and line -1 when unknown): the innermost inlined
 * function with the line of the address, then each function it was inlined into with the line of the call, the last
 * one being the function around the address. Null when nothing was inlined at the address. */
BIND(void *, jcl_stack_frameInlines0, "io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime.StackTraceSupport_frameInlines0",
     (int64_t address)) {
    const char *names[32];
    int lines[32];
    int n = nabu_frame_inlines((uintptr_t)address, names, lines, 32);
    if (n <= 0) return NULL;
    const char *outer = nabu_frame_name((uintptr_t)address);
    const char *scope = NULL, *row_file = NULL;
    const int row_line = nabu_frame_line((uintptr_t)address, &scope, &row_file);
    char *result = jcl_dup("");
    for (int i = 0; i <= n; i++) {
        const char *symbol = i < n ? names[i] : outer;
        if (symbol == NULL) break;
        const char *file = NULL;
        int line = -1;
        if (i == 0 && row_line > 0 && scope != NULL && strcmp(scope, symbol) == 0 && row_file != NULL && row_file[0] != '<') {
            file = row_file;
            line = row_line;
        } else if (i > 0) {
            file = nabu_symbol_file(symbol);
            line = lines[i - 1] > 0 ? lines[i - 1] : -1;
        }
        char num[16];
        snprintf(num, sizeof num, "%d", line);
        result = jcl_concat(result, symbol);
        result = jcl_concat(result, "\t");
        result = jcl_concat(result, file != NULL ? file : "");
        result = jcl_concat(result, "\t");
        result = jcl_concat(result, num);
        result = jcl_concat(result, "\n");
    }
    return nabu_jstring_new(result);
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
extern int32_t nabu_compiled_type_count_for_trace(void);
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
    const int64_t found = (int64_t)nabu_reflect_field_offset(cls_name, field);
    if (found < 0 && getenv("NABU_TRACE_UNSAFE") != NULL) {
        fprintf(stderr, "nabu: Unsafe.objectFieldOffset(%s, %s) not found; %d compiled types registered\n", cls_name, field,
                (int)nabu_compiled_type_count_for_trace());
    }
    return found;
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
BIND(void, jcl_Unsafe_fullFence, "jdk.internal.misc.Unsafe_fullFence", (void *self)) { (void)self; __atomic_thread_fence(__ATOMIC_SEQ_CST); }
BIND(void, jcl_Unsafe_ensureClassInitialized0, "jdk.internal.misc.Unsafe_ensureClassInitialized0", (void *self, void *cls)) { (void)self; (void)cls; }
BIND(int, jcl_Unsafe_shouldBeInitialized0, "jdk.internal.misc.Unsafe_shouldBeInitialized0", (void *self, void *cls)) { (void)self; (void)cls; return 0; }


BIND(int8_t, jcl_Unsafe_getByte, "jdk.internal.misc.Unsafe_getByte", (void *self, void *o, int64_t off)) { (void)self; int8_t v; memcpy(&v, UNSAFE_ADDR(o, off), sizeof v); return v; }
BIND(int16_t, jcl_Unsafe_getShort, "jdk.internal.misc.Unsafe_getShort", (void *self, void *o, int64_t off)) { (void)self; int16_t v; memcpy(&v, UNSAFE_ADDR(o, off), sizeof v); return v; }
BIND(uint16_t, jcl_Unsafe_getChar, "jdk.internal.misc.Unsafe_getChar", (void *self, void *o, int64_t off)) { (void)self; uint16_t v; memcpy(&v, UNSAFE_ADDR(o, off), sizeof v); return v; }
BIND(int32_t, jcl_Unsafe_getInt, "jdk.internal.misc.Unsafe_getInt", (void *self, void *o, int64_t off)) { (void)self; int32_t v; memcpy(&v, UNSAFE_ADDR(o, off), sizeof v); return v; }
BIND(int32_t, jcl_Unsafe_getByteVolatile, "jdk.internal.misc.Unsafe_getByteVolatile", (void *self, void *o, int64_t off)) { (void)self; return (int32_t)__atomic_load_n((int8_t *)UNSAFE_ADDR(o, off), __ATOMIC_SEQ_CST); }
BIND(int32_t, jcl_Unsafe_getIntVolatile, "jdk.internal.misc.Unsafe_getIntVolatile", (void *self, void *o, int64_t off)) { (void)self; return __atomic_load_n((int32_t *)UNSAFE_ADDR(o, off), __ATOMIC_SEQ_CST); }
BIND(int64_t, jcl_Unsafe_getLong, "jdk.internal.misc.Unsafe_getLong", (void *self, void *o, int64_t off)) { (void)self; int64_t v; memcpy(&v, UNSAFE_ADDR(o, off), sizeof v); return v; }
BIND(int64_t, jcl_Unsafe_getLongVolatile, "jdk.internal.misc.Unsafe_getLongVolatile", (void *self, void *o, int64_t off)) { (void)self; return __atomic_load_n((int64_t *)UNSAFE_ADDR(o, off), __ATOMIC_SEQ_CST); }
BIND(void *, jcl_Unsafe_getReference, "jdk.internal.misc.Unsafe_getReference", (void *self, void *o, int64_t off)) { (void)self; void * v; memcpy(&v, UNSAFE_ADDR(o, off), sizeof v); return v; }
BIND(void *, jcl_Unsafe_getReferenceVolatile, "jdk.internal.misc.Unsafe_getReferenceVolatile", (void *self, void *o, int64_t off)) { (void)self; return __atomic_load_n((void **)UNSAFE_ADDR(o, off), __ATOMIC_SEQ_CST); }
BIND(void, jcl_Unsafe_putByte, "jdk.internal.misc.Unsafe_putByte", (void *self, void *o, int64_t off, int8_t v)) { (void)self; memcpy(UNSAFE_ADDR(o, off), &v, sizeof v); }
BIND(void, jcl_Unsafe_putShort, "jdk.internal.misc.Unsafe_putShort", (void *self, void *o, int64_t off, int16_t v)) { (void)self; memcpy(UNSAFE_ADDR(o, off), &v, sizeof v); }
BIND(void, jcl_Unsafe_putInt, "jdk.internal.misc.Unsafe_putInt", (void *self, void *o, int64_t off, int32_t v)) { (void)self; memcpy(UNSAFE_ADDR(o, off), &v, sizeof v); }
BIND(void, jcl_Unsafe_putIntVolatile, "jdk.internal.misc.Unsafe_putIntVolatile", (void *self, void *o, int64_t off, int32_t v)) { (void)self; __atomic_store_n((int32_t *)UNSAFE_ADDR(o, off), v, __ATOMIC_SEQ_CST); }
BIND(void, jcl_Unsafe_putLong, "jdk.internal.misc.Unsafe_putLong", (void *self, void *o, int64_t off, int64_t v)) { (void)self; memcpy(UNSAFE_ADDR(o, off), &v, sizeof v); }
BIND(void, jcl_Unsafe_putLongVolatile, "jdk.internal.misc.Unsafe_putLongVolatile", (void *self, void *o, int64_t off, int64_t v)) { (void)self; __atomic_store_n((int64_t *)UNSAFE_ADDR(o, off), v, __ATOMIC_SEQ_CST); }
BIND(void, jcl_Unsafe_putFloat, "jdk.internal.misc.Unsafe_putFloat", (void *self, void *o, int64_t off, float v)) { (void)self; memcpy(UNSAFE_ADDR(o, off), &v, sizeof v); }
BIND(void, jcl_Unsafe_putDouble, "jdk.internal.misc.Unsafe_putDouble", (void *self, void *o, int64_t off, double v)) { (void)self; memcpy(UNSAFE_ADDR(o, off), &v, sizeof v); }
BIND(void, jcl_Unsafe_putReference, "jdk.internal.misc.Unsafe_putReference", (void *self, void *o, int64_t off, void * v)) { (void)self; memcpy(UNSAFE_ADDR(o, off), &v, sizeof v); }
BIND(void, jcl_Unsafe_putReferenceVolatile, "jdk.internal.misc.Unsafe_putReferenceVolatile", (void *self, void *o, int64_t off, void * v)) { (void)self; __atomic_store_n((void **)UNSAFE_ADDR(o, off), v, __ATOMIC_SEQ_CST); }
BIND(void, jcl_Unsafe_putBoolean, "jdk.internal.misc.Unsafe_putBoolean", (void *self, void *o, int64_t off, int v)) { (void)self; *(int8_t *)UNSAFE_ADDR(o, off) = (int8_t)(v != 0); }
BIND(void, jcl_Unsafe_putChar, "jdk.internal.misc.Unsafe_putChar$Pa86c5f3aP5f790d9cI64I16", (void *self, void *o, int64_t off, uint16_t v)) { (void)self; memcpy(UNSAFE_ADDR(o, off), &v, sizeof v); }


BIND(int, jcl_Unsafe_compareAndSetInt, "jdk.internal.misc.Unsafe_compareAndSetInt", (void *self, void *o, int64_t off, int32_t expected, int32_t x)) { (void)self; return __atomic_compare_exchange_n((int32_t *)UNSAFE_ADDR(o, off), &expected, x, 0, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST); }
BIND(int32_t, jcl_Unsafe_compareAndExchangeInt, "jdk.internal.misc.Unsafe_compareAndExchangeInt", (void *self, void *o, int64_t off, int32_t expected, int32_t x)) { (void)self; __atomic_compare_exchange_n((int32_t *)UNSAFE_ADDR(o, off), &expected, x, 0, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST); return expected; }
BIND(int, jcl_Unsafe_compareAndSetLong, "jdk.internal.misc.Unsafe_compareAndSetLong", (void *self, void *o, int64_t off, int64_t expected, int64_t x)) { (void)self; return __atomic_compare_exchange_n((int64_t *)UNSAFE_ADDR(o, off), &expected, x, 0, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST); }
BIND(int, jcl_Unsafe_compareAndSetReference, "jdk.internal.misc.Unsafe_compareAndSetReference", (void *self, void *o, int64_t off, void * expected, void * x)) { (void)self; return __atomic_compare_exchange_n((void **)UNSAFE_ADDR(o, off), &expected, x, 0, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST); }
BIND(int64_t, jcl_Unsafe_compareAndExchangeLong, "jdk.internal.misc.Unsafe_compareAndExchangeLong", (void *self, void *o, int64_t off, int64_t expected, int64_t x)) { (void)self; __atomic_compare_exchange_n((int64_t *)UNSAFE_ADDR(o, off), &expected, x, 0, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST); return expected; }
BIND(void *, jcl_Unsafe_compareAndExchangeReference, "jdk.internal.misc.Unsafe_compareAndExchangeReference", (void *self, void *o, int64_t off, void * expected, void * x)) { (void)self; __atomic_compare_exchange_n((void **)UNSAFE_ADDR(o, off), &expected, x, 0, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST); return expected; }
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
    void *seen = __atomic_load_n(&thread, __ATOMIC_ACQUIRE);
    if (seen != NULL) return seen;
    void *made = jcl_new_thread("main", NULL);
    void *none = NULL;
    if (__atomic_compare_exchange_n(&thread, &none, made, 0, __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) return made;
    return none;
}

/* The Thread object of the running thread: the thread's own, or "main". */
/* Continuations need the Boehm collector, and fibers (Windows) or ucontext (Linux and macOS, x86-64 and aarch64).
 * They are on unless NABU_CONTINUATIONS=0. */
#if defined(NABU_GC_BOEHM) && (defined(_WIN32) || ((defined(__linux__) || defined(__APPLE__)) && (defined(__x86_64__) || defined(__aarch64__))))
#define JCL_CONTINUATIONS_PLATFORM 1
#else
#define JCL_CONTINUATIONS_PLATFORM 0
#endif
static int jcl_continuations_enabled(void) {
#if JCL_CONTINUATIONS_PLATFORM
    const char *flag = getenv("NABU_CONTINUATIONS");
    return flag == NULL || flag[0] != '0';
#else
    return 0;
#endif
}
/* ThreadSupport.backoffVirtual(int): a virtual thread that waits for a monitor parks (and lets go of its carrier)
 * until the release of the monitor unparks it, with a bound as a safety net. */
static void (*jcl_virtual_backoff_fn)(int) = NULL;
static void (*jcl_virtual_unpark_fn)(void *) = NULL;
static void *jcl_monitor_vthread(void);
static void jcl_monitor_park(void);
static void jcl_monitor_unpark(void *vthread);
void nabu_set_virtual_backoff_fn(void *fn) {
    jcl_virtual_backoff_fn = (void (*)(int))fn;
    nabu_set_monitor_virtual_fns(jcl_monitor_vthread, jcl_monitor_park, jcl_monitor_unpark);
}
/* ContinuationBridge.startRoot: the start() of a virtual thread on a continuation. */
static void (*jcl_virtual_start_fn)(void *) = NULL;
void nabu_set_virtual_start_fn(void *fn) { jcl_virtual_start_fn = (void (*)(void *))fn; }

static __thread void *jcl_cur_thread = NULL;
/* The virtual thread that is mounted on this thread, else NULL: Thread.currentThread() is that one, the thread
 * itself (the carrier) is what Thread.currentCarrierThread() and everything that blocks work on. */
static __thread void *jcl_logical_thread = NULL;
/* The virtual thread that is mounted here and may be unmounted while it waits for a monitor, else NULL. */
static void *jcl_monitor_vthread(void) {
    if (jcl_logical_thread == NULL || jcl_virtual_backoff_fn == NULL || jcl_virtual_unpark_fn == NULL
            || !jcl_continuations_enabled()) return NULL;
    return jcl_logical_thread;
}
static void jcl_monitor_park(void) { jcl_virtual_backoff_fn(0); }
static void jcl_monitor_unpark(void *vthread) { jcl_virtual_unpark_fn(vthread); }
static void *jcl_carrier_thread(void) { return jcl_cur_thread != NULL ? jcl_cur_thread : jcl_main_thread(); }
static void *jcl_current_thread(void) { return jcl_logical_thread != NULL ? jcl_logical_thread : jcl_carrier_thread(); }

static s_thread *jcl_thread_of(void *self) {
    s_thread *st = jcl_thread_state(self);
    if (st == NULL) st = jcl_thread_state(jcl_current_thread());
    return st;
}

/* new Thread(...): the arguments are told apart by what they are: a ThreadGroup is the group, a String the name
 * and the first other object the Runnable. The backend calls Thread_init$<n>. A thread without a group gets the
 * group of the thread that makes it (NULL is the main group, see ThreadSupport.getThreadGroup). */
static s_thread *jcl_thread_new_state(void) {
    s_thread *st = (s_thread *)jcl_alloc(sizeof(s_thread));
    static int64_t next_id = 2;
    st->kind = K_THREAD;
    st->priority = 5;
    st->id = next_id++;
    st->group = jcl_thread_state(jcl_current_thread())->group;
    st->inherit_from = jcl_thread_state(jcl_current_thread())->inheritableThreadLocals;
    return st;
}

static void jcl_thread_name(s_thread *st, const char *name) {
    if (name != NULL) {
        st->name = jcl_dup(name);
    } else {
        char generated[32];
        static int generated_count = 0;
        snprintf(generated, sizeof generated, "Thread-%d", __atomic_fetch_add(&generated_count, 1, __ATOMIC_SEQ_CST));
        st->name = jcl_dup(generated);
    }
}

static void jcl_thread_construct(void *self, int n, void **args) {
    s_thread *st = jcl_thread_new_state();
    const char *name = NULL;
    for (int i = 0; i < n && i < 3; i++) {
        if (args[i] == NULL) continue;
        if (nabu_compiled_type_name(args[i]) != NULL && nabu_instanceof(args[i], "java/lang/ThreadGroup")) st->group = args[i];
        else if (nabu_is_jstring(args[i]) || (!nabu_jcl_is_object(args[i]) && nabu_compiled_type_name(args[i]) == NULL)
                || jcl_state_kind(args[i], K_STR) != NULL) name = nabu_cstr(args[i]);
        else if (st->target == NULL) st->target = args[i];
    }
    jcl_thread_name(st, name);
    nabu_jcl_set_state(self, st);
}
BIND(void, jcl_thread_init_0, "java.lang.Thread_init$0", (void *self)) { jcl_thread_construct(self, 0, NULL); }
BIND(void, jcl_thread_init_1, "java.lang.Thread_init$1", (void *self, void *a)) { void *args[1] = {a}; jcl_thread_construct(self, 1, args); }
BIND(void, jcl_thread_init_2, "java.lang.Thread_init$2", (void *self, void *a, void *b)) { void *args[2] = {a, b}; jcl_thread_construct(self, 2, args); }
BIND(void, jcl_thread_init_3, "java.lang.Thread_init$3", (void *self, void *a, void *b, void *c)) { void *args[3] = {a, b, c}; jcl_thread_construct(self, 3, args); }
BIND(void, jcl_thread_init_4, "java.lang.Thread_init$4", (void *self, void *a, void *b, void *c, int64_t d)) { void *args[3] = {a, b, c}; (void)d; jcl_thread_construct(self, 4, args); }
BIND(void, jcl_thread_init_5, "java.lang.Thread_init$5", (void *self, void *a, void *b, void *c, int64_t d, int e)) { void *args[3] = {a, b, c}; (void)d; (void)e; jcl_thread_construct(self, 5, args); }
/* Thread(ThreadGroup, String, int characteristics, Runnable, long stackSize, AccessControlContext) of the builders */
BIND(void, jcl_thread_init_6, "java.lang.Thread_init$6", (void *self, void *group, void *name, int characteristics, void *task, int64_t stack, void *acc)) {
    (void)characteristics; (void)stack; (void)acc;
    void *args[3] = {group, name, task};
    jcl_thread_construct(self, 3, args);
}
/* Thread(String, int characteristics, boolean bound): a virtual thread, which here is bound to a native thread */
BIND(void, jcl_thread_init_vt, "java.lang.Thread_init$vt", (void *self, void *name, int characteristics, int bound)) {
    (void)characteristics; (void)bound;
    s_thread *st = jcl_thread_new_state();
    st->name = jcl_dup(name != NULL ? nabu_cstr(name) : "");
    st->daemon = 1;
    st->is_virtual = 1;
    nabu_jcl_set_state(self, st);
}
/* ---------------------------------------------------------------
 * Threads: real native threads that run one at a time (nabu_gil_*), switching where a thread blocks.
 * --------------------------------------------------------------- */
#ifdef NABU_GC_BOEHM
extern int GC_pthread_create(pthread_t *thread, const pthread_attr_t *attr, void *(*start)(void *), void *arg);
#define JCL_PTHREAD_CREATE GC_pthread_create
#else
#define JCL_PTHREAD_CREATE pthread_create
#endif

static void (*jcl_thread_run_fn)(void *) = NULL;
void nabu_set_thread_run_fn(void *fn) { jcl_thread_run_fn = (void (*)(void *))fn; }

static volatile int jcl_live_threads = 0;

static int64_t jcl_now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static void jcl_throw_new(const char *cls, const char *super, const char *msg) {
    nabu_throw(jcl_throw(cls, super, msg));
}

/* vthread: a virtual thread that waits (Object.wait on a continuation) is woken by unparking it, a thread by its waiter. */
#ifdef NABU_GC_BOEHM
extern void *GC_malloc_uncollectable(size_t n);
extern void GC_free(void *p);
#else
/* without the collector nothing is collected: plain memory is as good as a root */
#define GC_malloc_uncollectable(n) calloc(1, (n))
#define GC_free(p) free(p)
#endif
typedef struct jcl_waiter { void *obj; volatile int notified; struct jcl_waiter *next; nabu_waiter *waiter; void *vthread; int depth; } jcl_waiter;
void nabu_set_virtual_unpark_fn(void *fn) { jcl_virtual_unpark_fn = (void (*)(void *))fn; }
static jcl_waiter *jcl_waiters = NULL;
/* The waiters list is shared by all threads; with the GIL only one thread touches it at a time, in parallel mode
 * this lock guards it. */
static pthread_mutex_t jcl_waiters_lock = PTHREAD_MUTEX_INITIALIZER;

static void jcl_notify(void *obj, int all) {
    for (;;) {
        void *virtuals[64];
        int woken = 0, counted = 0;
        pthread_mutex_lock(&jcl_waiters_lock);
        for (jcl_waiter *w = jcl_waiters; w != NULL && counted < 64; w = w->next) {
            if (w->obj == obj && !w->notified) {
                w->notified = 1;
                counted++;
                if (w->vthread != NULL) virtuals[woken++] = w->vthread; else nabu_waiter_wake(w->waiter);
                if (!all) break;
            }
        }
        pthread_mutex_unlock(&jcl_waiters_lock);
        /* unparked outside the lock: that is Java code */
        for (int i = 0; i < woken; i++) if (jcl_virtual_unpark_fn != NULL) jcl_virtual_unpark_fn(virtuals[i]);
        if (!all || counted < 64) break;
    }
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
    s_thread *cur = jcl_thread_of(jcl_carrier_thread());
    jcl_check_interrupt(cur);
    if (!nabu_threads_active) {
        /* Nobody can notify: return as a spurious wakeup, after the time. */
        if (timeout > 0) jcl_sleep_ms(timeout);
        return;
    }
    const int me = nabu_current_thread_id();
    const int32_t depth = h->monitor_count;
    nabu_waiter *me_waiter = nabu_waiter_self();
    jcl_waiter w = { self, 0, NULL, me_waiter, NULL, 0 };
    pthread_mutex_lock(&jcl_waiters_lock);
    jcl_waiter **tail = &jcl_waiters;
    while (*tail != NULL) tail = &(*tail)->next;
    *tail = &w;
    pthread_mutex_unlock(&jcl_waiters_lock);
    h->monitor_count = 0;
    __atomic_store_n(&h->thread_id, 0, __ATOMIC_SEQ_CST);
    if (__atomic_load_n(&nabu_monitor_waiters, __ATOMIC_SEQ_CST) > 0 || !nabu_parallel) {
        nabu_keyq_wake_one(self);
    }
    const int64_t start = jcl_now_ms();
    nabu_wait_mark(timeout > 0 ? 1 : 2);
    for (;;) {
        const unsigned long ticket = nabu_waiter_ticket(me_waiter);
        if (w.notified || cur->interrupted) break;
        int64_t left = -1;
        if (timeout > 0) {
            left = timeout - (jcl_now_ms() - start);
            if (left <= 0) break;
        }
        nabu_waiter_wait(me_waiter, ticket, left);
    }
    pthread_mutex_lock(&jcl_waiters_lock);
    for (jcl_waiter **p = &jcl_waiters; *p != NULL; p = &(*p)->next) {
        if (*p == &w) { *p = w.next; break; }
    }
    pthread_mutex_unlock(&jcl_waiters_lock);
    nabu_wait_mark(3);
    nabu_keyq_add(self, me_waiter);
    __atomic_add_fetch(&nabu_monitor_waiters, 1, __ATOMIC_SEQ_CST);
    for (;;) {
        const unsigned long ticket = nabu_waiter_ticket(me_waiter);
        int free_word = 0;
        if (__atomic_compare_exchange_n(&h->thread_id, &free_word, me, 0, __ATOMIC_ACQUIRE, __ATOMIC_RELAXED)) break;
        nabu_waiter_wait(me_waiter, ticket, -1);
    }
    __atomic_sub_fetch(&nabu_monitor_waiters, 1, __ATOMIC_SEQ_CST);
    nabu_keyq_remove(self, me_waiter);
    nabu_wait_mark(0);
    h->monitor_count = depth;
    if (!w.notified) jcl_check_interrupt(cur);
}

/* Object.wait of a virtual thread on a continuation, in three steps (ThreadSupport.virtualWait): enter registers the
 * thread as a waiter and gives up the monitor; the thread then parks (which unmounts it) until done says it was
 * notified; leave takes the monitor back. Returns 0 when this cannot be done (threads are not parallel). */
BIND(int64_t, jcl_ThreadSupport_waitEnter0, "io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime.ThreadSupport_waitEnter0", (void *object, void *thread)) {
    if (!jcl_owns_monitor(object)) jcl_throw_new("java/lang/IllegalMonitorStateException", "java/lang/RuntimeException", "current thread is not owner");
    if (!nabu_threads_active || !nabu_parallel) return 0;
    /* a root: the waiting virtual thread may be referred to only from here */
    jcl_waiter *w = (jcl_waiter *)GC_malloc_uncollectable(sizeof *w);
    w->obj = object;
    w->vthread = thread;
    pthread_mutex_lock(&jcl_waiters_lock);
    jcl_waiter **tail = &jcl_waiters;
    while (*tail != NULL) tail = &(*tail)->next;
    *tail = w;
    pthread_mutex_unlock(&jcl_waiters_lock);
    w->depth = nabu_monitor_release_all(object);
    return (int64_t)(intptr_t)w;
}
BIND(int, jcl_ThreadSupport_waitDone0, "io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime.ThreadSupport_waitDone0", (int64_t handle)) {
    return ((jcl_waiter *)(intptr_t)handle)->notified;
}
BIND(int, jcl_ThreadSupport_waitLeave0, "io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime.ThreadSupport_waitLeave0", (int64_t handle)) {
    jcl_waiter *w = (jcl_waiter *)(intptr_t)handle;
    pthread_mutex_lock(&jcl_waiters_lock);
    for (jcl_waiter **p = &jcl_waiters; *p != NULL; p = &(*p)->next) {
        if (*p == w) { *p = w->next; break; }
    }
    pthread_mutex_unlock(&jcl_waiters_lock);
    nabu_monitor_acquire_n(w->obj, w->depth);
    const int notified = w->notified;
    GC_free(w);
    return notified;
}

BIND(void, jcl_ThreadSupport_waitOn0, "io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime.ThreadSupport_waitOn0", (void *object, int64_t millis)) {
    jcl_Object_wait0(object, millis);
}

BIND(int, jcl_ThreadSupport_stateOf0, "io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime.ThreadSupport_stateOf0", (void *thread)) {
    s_thread *st = jcl_thread_of(thread);
    /* 0 new, 2 terminated, 10 + what a started thread waits for (0 runs, 1 timed waiting, 2 waiting, 3 blocked) */
    return st->state == 1 ? 10 + st->wait_state : st->state;
}
BIND(void *, jcl_ThreadSupport_parentLocals0, "io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime.ThreadSupport_parentLocals0", (void *thread)) {
    return jcl_thread_of(thread)->inherit_from;
}
BIND(void, jcl_ThreadSupport_setLocals0, "io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime.ThreadSupport_setLocals0", (void *thread, void *map)) {
    jcl_thread_of(thread)->inheritableThreadLocals = map;
}

BIND(void, jcl_java_lang_Thread_sleep, "java.lang.Thread_sleep", (int64_t ms)) {
    s_thread *cur = jcl_thread_of(jcl_carrier_thread());
    jcl_check_interrupt(cur);
    if (ms <= 0) return;
    if (!nabu_threads_active) { jcl_sleep_ms(ms); return; }
    const int64_t start = jcl_now_ms();
    nabu_wait_mark(1);
    nabu_waiter *waiter = nabu_waiter_self();
    for (;;) {
        const unsigned long ticket = nabu_waiter_ticket(waiter);
        if (cur->interrupted) break;
        int64_t left = ms - (jcl_now_ms() - start);
        if (left <= 0) break;
        nabu_waiter_wait(waiter, ticket, left);
    }
    nabu_wait_mark(0);
    jcl_check_interrupt(cur);
}

BIND(void, jcl_Unsafe_park, "jdk.internal.misc.Unsafe_park", (void *self, int absolute, int64_t time)) {
    (void)self;
    s_thread *cur = jcl_thread_of(jcl_carrier_thread());
    if (__atomic_exchange_n(&cur->permit, 0, __ATOMIC_ACQ_REL)) return;
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
    nabu_wait_mark(ms >= 0 ? 1 : 2);
    nabu_waiter *waiter = nabu_waiter_self();
    for (;;) {
        const unsigned long ticket = nabu_waiter_ticket(waiter);
        if (__atomic_load_n(&cur->permit, __ATOMIC_SEQ_CST) || cur->interrupted) break;
        int64_t left = -1;
        if (ms >= 0) {
            left = ms - (jcl_now_ms() - start);
            if (left <= 0) break;
        }
        nabu_waiter_wait(waiter, ticket, left);
    }
    nabu_wait_mark(0);
    __atomic_store_n(&cur->permit, 0, __ATOMIC_SEQ_CST);
}
BIND(void, jcl_Unsafe_unpark, "jdk.internal.misc.Unsafe_unpark", (void *self, void *thread)) {
    (void)self;
    s_thread *st = thread == NULL ? NULL : jcl_thread_state(thread);
    if (st == NULL) return;
    __atomic_store_n(&st->permit, 1, __ATOMIC_SEQ_CST);
    nabu_waiter_wake(st->waiter);
}

BIND(void, jcl_Thread_yield, "java.lang.Thread_yield", (void)) { nabu_gil_yield(); }
BIND(void, jcl_Thread_onSpinWait, "java.lang.Thread_onSpinWait", (void)) { nabu_gil_yield(); }

BIND(int, jcl_Thread_interrupted, "java.lang.Thread_interrupted", (void)) {
    s_thread *cur = jcl_thread_of(jcl_current_thread());
    int was = cur->interrupted;
    cur->interrupted = 0;
    return was;
}
BIND(int, jcl_Thread_isInterrupted, "java.lang.Thread_isInterrupted", (void *self)) {
    s_thread *st = jcl_thread_of(self);
    nabu_spin_hint(&st->interrupted);
    return st->interrupted;
}
BIND(void, jcl_Thread_clearInterrupt, "java.lang.Thread_clearInterrupt", (void *self)) { jcl_thread_of(self)->interrupted = 0; }
/* The bridge that calls Interruptible.interrupt(Thread) of the channel a thread is blocked on (see InterruptBridge). */
static void (*jcl_nio_interrupt_fn)(void *, void *) = NULL;
void nabu_set_nio_interrupt_fn(void *fn) { jcl_nio_interrupt_fn = (void (*)(void *, void *))fn; }
BIND(void, jcl_Thread_interrupt, "java.lang.Thread_interrupt", (void *self)) {
    s_thread *target = jcl_thread_of(self);
    target->interrupted = 1;
    nabu_waiter_wake(target->waiter);
    /* Like the JDK: another thread's interrupt closes the channel it is blocked on, which wakes it. */
    void *blocker = target->nio_blocker;
    if (blocker != NULL && jcl_nio_interrupt_fn != NULL && target != jcl_thread_of(jcl_carrier_thread())) {
        jcl_nio_interrupt_fn(blocker, self);
    }
}
BIND(int, jcl_java_lang_Thread_isAlive, "java.lang.Thread_isAlive", (void *self)) {
    s_thread *st = jcl_thread_of(self);
    nabu_spin_hint(&st->state);
    return st->state == 1;
}

/* A thread that was created but has not registered itself with the collector yet is not scanned (Windows registers a
 * thread from inside the thread), and nothing else need refer to its Thread object: a ForkJoinPool only knows its
 * worker once the worker runs. On one core the new thread can wait a long time for its first turn, and a collection in
 * between freed the Thread object and its state. Until the thread runs, the object is kept here, in static memory that
 * the collector scans. */
typedef struct jcl_starting { void *self; struct jcl_starting *next; } jcl_starting;
static jcl_starting *jcl_starting_head = NULL;
static pthread_mutex_t jcl_starting_lock = PTHREAD_MUTEX_INITIALIZER;

static void jcl_starting_add(void *self) {
    jcl_starting *node = (jcl_starting *)jcl_alloc(sizeof(jcl_starting));
    node->self = self;
    pthread_mutex_lock(&jcl_starting_lock);
    node->next = jcl_starting_head;
    jcl_starting_head = node;
    pthread_mutex_unlock(&jcl_starting_lock);
}

static void jcl_starting_remove(void *self) {
    pthread_mutex_lock(&jcl_starting_lock);
    for (jcl_starting **link = &jcl_starting_head; *link != NULL; link = &(*link)->next) {
        if ((*link)->self == self) {
            *link = (*link)->next;
            break;
        }
    }
    pthread_mutex_unlock(&jcl_starting_lock);
}

/* The body of a native thread: take the turn, run Thread.run through the program's ThreadSupport, tell the joiners. */
static void *jcl_thread_main(void *arg) {
    s_thread *st = (s_thread *)arg;
    nabu_gil_acquire();
    jcl_cur_thread = st->self;
    jcl_starting_remove(st->self);
    nabu_set_current_thread_id((int)st->id);
    nabu_set_wait_slot(&st->wait_state);
    __atomic_store_n(&st->waiter, nabu_waiter_self(), __ATOMIC_SEQ_CST);
    if (jcl_thread_run_fn != NULL) jcl_thread_run_fn(st->self);
    /* Under the monitor of the thread, as the JVM does: Thread.join holds it from its look at isAlive until it
     * waits, so the end of the thread cannot come in between and its notification be missed. */
    nabu_monitorenter(st->self);
    st->state = 2;
    if (!st->daemon) __atomic_fetch_sub(&jcl_live_threads, 1, __ATOMIC_SEQ_CST);
    jcl_notify(st->self, 1);
    nabu_monitorexit(st->self);
    nabu_keyq_wake((const void *) &jcl_live_threads);
    nabu_gil_release();
    return NULL;
}

/* At exit the JVM waits for the threads that are not daemons, unless System.exit was called. */
static void jcl_await_threads(void) {
    if (jcl_exiting) return;
    nabu_waiter *waiter = nabu_waiter_self();
    nabu_keyq_add((const void *) &jcl_live_threads, waiter);
    for (;;) {
        const unsigned long ticket = nabu_waiter_ticket(waiter);
        if (jcl_live_threads <= 0) break;
        nabu_waiter_wait(waiter, ticket, -1);
    }
    nabu_keyq_remove((const void *) &jcl_live_threads, waiter);
}

/* The stack trace of another thread, asked cooperatively: the thread that wants it sets a request and wakes the
 * thread, and the thread gives its own stack where it is not busy (the wake hook of the waiters). A thread that
 * runs compiled code without ever waiting answers at the next jump back of a loop (nabu_safepoint_poll). */
static pthread_mutex_t jcl_capture_lock = PTHREAD_MUTEX_INITIALIZER;

static void jcl_capture_hook(void) {
    s_thread *st = jcl_thread_state(jcl_carrier_thread());
    if (st == NULL || !__atomic_load_n(&st->cap_request, __ATOMIC_SEQ_CST)) return;
    uintptr_t frames[128];
    int n = nabu_capture_frames(frames, 128);
    /* the frames of the runtime itself (this hook, the wait) are not part of the trace */
    int first = 0;
    while (first < n) {
        const char *name = nabu_frame_name(frames[first]);
        if (name == NULL || strncmp(name, "nabu_", 5) == 0 || strncmp(name, "jcl_", 4) == 0) first++; else break;
    }
    st->cap_count = n - first;
    memcpy(st->cap_frames, frames + first, (size_t)(n - first) * sizeof(uintptr_t));
    __atomic_store_n(&st->cap_request, 0, __ATOMIC_SEQ_CST);
    __atomic_store_n(&st->cap_done, 1, __ATOMIC_SEQ_CST);
    nabu_waiter_wake(st->cap_requester);
}

/* ThreadSupport.stackTrace0(Thread): the return addresses of the frames of a running thread, or null. */
BIND(void *, jcl_ThreadSupport_stackTrace0, "io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime.ThreadSupport_stackTrace0",
     (void *thread)) {
    s_thread *st = jcl_thread_state(thread);
    if (st == NULL || st->state != 1 || !nabu_threads_active) return NULL;
    nabu_waiter *self = nabu_waiter_self();
    int found = 0;
    uintptr_t frames[128];
    int n = 0;
    pthread_mutex_lock(&jcl_capture_lock);
    __atomic_add_fetch(&nabu_safepoint_request, 1, __ATOMIC_SEQ_CST);
    st->cap_done = 0;
    st->cap_requester = self;
    __atomic_store_n(&st->cap_request, 1, __ATOMIC_SEQ_CST);
    const int64_t until = jcl_now_ms() + 1000;
    for (;;) {
        const unsigned long ticket = nabu_waiter_ticket(self);
        if (__atomic_load_n(&st->cap_done, __ATOMIC_SEQ_CST)) { found = 1; break; }
        const int64_t left = until - jcl_now_ms();
        if (left <= 0) break;
        /* wake again and again: a wake just before the thread reads its ticket is not seen by the wait, and the hook
         * runs only when a wait returns */
        nabu_waiter_wake(__atomic_load_n(&st->waiter, __ATOMIC_SEQ_CST));
        nabu_waiter_wait(self, ticket, left < 5 ? left : 5);
    }
    __atomic_store_n(&st->cap_request, 0, __ATOMIC_SEQ_CST);
    __atomic_sub_fetch(&nabu_safepoint_request, 1, __ATOMIC_SEQ_CST);
    if (found) {
        n = st->cap_count;
        memcpy(frames, st->cap_frames, (size_t)n * sizeof(uintptr_t));
    }
    pthread_mutex_unlock(&jcl_capture_lock);
    if (!found) return NULL;
    void *arr = jcl_alloc(8 + (size_t)n * sizeof(int64_t));
    ((int32_t *)arr)[0] = n;
    ((int32_t *)arr)[1] = 8;
    nabu_array_tag(arr, "[J");
    for (int i = 0; i < n; i++) ((int64_t *)((char *)arr + 8))[i] = (int64_t)frames[i];
    return arr;
}

#if defined(_WIN32) && defined(NABU_GC_BOEHM)
/* On Windows a thread made by pthread_create gets its whole stack committed at once. A thread made by CreateThread
 * with STACK_SIZE_PARAM_IS_A_RESERVATION only reserves the address space and commits the pages it touches, so a
 * big stack costs nothing until it is used. The collector is told about the thread by hand. */
extern void GC_allow_register_threads(void);
extern int GC_register_my_thread(const struct GC_stack_base *base);
extern int GC_unregister_my_thread(void);

static DWORD WINAPI jcl_windows_thread_main(LPVOID arg) {
    struct GC_stack_base base;
    GC_get_stack_base(&base);
    GC_register_my_thread(&base);
    jcl_thread_main(arg);
    GC_unregister_my_thread();
    return 0;
}
#endif

BIND(void, jcl_java_lang_Thread_start, "java.lang.Thread_start", (void *self)) {
    s_thread *st = jcl_thread_state(self);
    /* A virtual thread on a continuation is started by the JDK's VirtualThread (it is scheduled on a carrier). */
    if (st != NULL && st->is_virtual && jcl_continuations_enabled() && jcl_virtual_start_fn != NULL) {
        jcl_virtual_start_fn(self);
        return;
    }
    if (st == NULL || st->state != 0) jcl_throw_new("java/lang/IllegalThreadStateException", "java/lang/IllegalArgumentException", NULL);
    if (!nabu_threads_active) {
        /* Threads run in parallel; NABU_GIL=1 makes them run one at a time again (see nabu_gil_*). */
        const char *gil = getenv("NABU_GIL");
        nabu_parallel = !(gil != NULL && gil[0] == '1');
        nabu_spin_hints = !nabu_parallel;
        nabu_threads_active = 1;
        nabu_set_wake_hook(jcl_capture_hook);
#if defined(_WIN32) && defined(NABU_GC_BOEHM)
        GC_allow_register_threads();
#endif
        nabu_gil_acquire();
        nabu_set_wait_slot(&jcl_thread_of(jcl_current_thread())->wait_state);
        jcl_thread_of(jcl_current_thread())->waiter = nabu_waiter_self();
        atexit(jcl_await_threads);
    }
    st->state = 1;
    st->self = self;
    if (!st->daemon) __atomic_fetch_add(&jcl_live_threads, 1, __ATOMIC_SEQ_CST);
    /* A virtual thread is meant to be one of very many: a smaller stack than a platform thread. */
    const size_t stack_size = st->is_virtual ? (size_t)4 << 20 : (size_t)16 << 20;
    jcl_starting_add(self);
#if defined(_WIN32) && defined(NABU_GC_BOEHM)
    HANDLE created = CreateThread(NULL, stack_size, jcl_windows_thread_main, st, STACK_SIZE_PARAM_IS_A_RESERVATION, NULL);
    int rc = created != NULL ? 0 : -1;
    if (created != NULL) CloseHandle(created);
#else
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
    pthread_attr_setstacksize(&attr, stack_size);
    pthread_t handle;
    int rc = JCL_PTHREAD_CREATE(&handle, &attr, jcl_thread_main, st);
    pthread_attr_destroy(&attr);
#endif
    if (rc != 0) {
        jcl_starting_remove(self);
        st->state = 2;
        if (!st->daemon) __atomic_fetch_sub(&jcl_live_threads, 1, __ATOMIC_SEQ_CST);
        jcl_throw_new("java/lang/IllegalThreadStateException", "java/lang/IllegalArgumentException", "unable to create native thread");
    }
}

BIND(void *, jcl_Thread_threadLocals, "java.lang.Thread_threadLocals", (void *self)) { return jcl_thread_of(self)->threadLocals; }
BIND(void, jcl_Thread_setThreadLocals, "java.lang.Thread_setThreadLocals", (void *self, void *map)) { jcl_thread_of(self)->threadLocals = map; }
BIND(void *, jcl_Thread_inheritableThreadLocals, "java.lang.Thread_inheritableThreadLocals", (void *self)) { return jcl_thread_of(self)->inheritableThreadLocals; }
BIND(void, jcl_Thread_setInheritableThreadLocals, "java.lang.Thread_setInheritableThreadLocals", (void *self, void *map)) { jcl_thread_of(self)->inheritableThreadLocals = map; }
BIND(void *, jcl_Thread_terminatingThreadLocals, "java.lang.Thread_terminatingThreadLocals", (void *self)) { (void)self; return NULL; }
BIND(void, jcl_Thread_setTerminatingThreadLocals, "java.lang.Thread_setTerminatingThreadLocals", (void *self, void *map)) { (void)self; (void)map; }
/* Scoped values (ScopedValue): the bindings and the cache of the running thread. A thread starts without bindings
 * (NULL); findScopedValueBindings, which the JVM answers by walking the stack, then also gives NULL: no bindings. */
BIND(void *, jcl_Thread_threadContainer, "java.lang.Thread_threadContainer", (void *self)) { return jcl_thread_of(self)->container; }
BIND(void, jcl_Thread_setThreadContainer, "java.lang.Thread_setThreadContainer", (void *self, void *container)) { jcl_thread_of(self)->container = container; }
BIND(void, jcl_ThreadSupport_setContainer0, "io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime.ThreadSupport_setContainer0", (void *thread, void *container)) { jcl_thread_of(thread)->container = container; }
BIND(void *, jcl_ThreadSupport_containerOf0, "io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime.ThreadSupport_containerOf0", (void *thread)) { return jcl_thread_of(thread)->container; }
BIND(void, jcl_ThreadSupport_inheritBindings0, "io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime.ThreadSupport_inheritBindings0", (void *thread)) { jcl_thread_of(thread)->sv_bindings = jcl_thread_of(jcl_current_thread())->sv_bindings; }
BIND(void *, jcl_Thread_scopedValueBindings, "java.lang.Thread_scopedValueBindings", (void)) { return jcl_thread_of(jcl_current_thread())->sv_bindings; }
BIND(void, jcl_Thread_setScopedValueBindings, "java.lang.Thread_setScopedValueBindings", (void *bindings)) { jcl_thread_of(jcl_current_thread())->sv_bindings = bindings; }
BIND(void *, jcl_Thread_findScopedValueBindings, "java.lang.Thread_findScopedValueBindings", (void)) { return NULL; }
BIND(void *, jcl_Thread_scopedValueCache, "java.lang.Thread_scopedValueCache", (void)) { return jcl_thread_of(jcl_carrier_thread())->sv_cache; }
BIND(void, jcl_Thread_setScopedValueCache, "java.lang.Thread_setScopedValueCache", (void *cache)) { jcl_thread_of(jcl_carrier_thread())->sv_cache = cache; }
BIND(void *, jcl_Thread_headStackableScopes, "java.lang.Thread_headStackableScopes", (void *self)) { return jcl_thread_of(self)->sv_scope; }
BIND(void, jcl_Thread_setHeadStackableScope, "java.lang.Thread_setHeadStackableScope", (void *scope)) { jcl_thread_of(jcl_current_thread())->sv_scope = scope; }
BIND(void, jcl_Thread_ensureMaterializedForStackWalk, "java.lang.Thread_ensureMaterializedForStackWalk", (void *o)) { (void)o; }
BIND(void *, jcl_Thread_currentCarrierThread, "java.lang.Thread_currentCarrierThread", (void)) { return jcl_carrier_thread(); }
/* carrier.setCurrentThread(thread): a virtual thread is mounted on (or unmounted from) the running thread. It takes
 * the identity of the thread for monitors with it. */
BIND(void, jcl_Thread_setCurrentThread, "java.lang.Thread_setCurrentThread", (void *self, void *thread)) {
    (void)self;
    jcl_logical_thread = thread == jcl_carrier_thread() ? NULL : thread;
    nabu_set_current_thread_id((int)jcl_thread_of(thread)->id);
}
BIND(void *, jcl_Thread_getThreadGroup, "java.lang.Thread_getThreadGroup", (void *self)) { return jcl_thread_of(self)->group; }
BIND(void, jcl_Thread_blockedOn, "java.lang.Thread_blockedOn", (void *self, void *interruptible)) {
    jcl_thread_of(self)->nio_blocker = interruptible;
}
BIND(void, jcl_Thread_setDaemon, "java.lang.Thread_setDaemon", (void *self, int on)) { jcl_thread_of(self)->daemon = on; }
BIND(int, jcl_Thread_isDaemon, "java.lang.Thread_isDaemon", (void *self)) { return jcl_thread_of(self)->daemon; }
BIND(int, jcl_Thread_getPriority, "java.lang.Thread_getPriority", (void *self)) { return jcl_thread_of(self)->priority; }
BIND(int64_t, jcl_Thread_getId, "java.lang.Thread_getId", (void *self)) { return jcl_thread_of(self)->id; }
BIND(void, jcl_Thread_setPriority, "java.lang.Thread_setPriority", (void *self, int p)) { jcl_thread_of(self)->priority = p; }
BIND(void, jcl_Thread_setName, "java.lang.Thread_setName", (void *self, void *name)) { jcl_thread_of(self)->name = jcl_dup(nabu_cstr(name)); }
BIND(void, jcl_Thread_setContextClassLoader, "java.lang.Thread_setContextClassLoader", (void *self, void *loader)) { (void)self; (void)loader; }
BIND(void, jcl_Thread_setUncaughtExceptionHandler, "java.lang.Thread_setUncaughtExceptionHandler", (void *self, void *handler)) { jcl_thread_of(self)->handler = handler; }
BIND(void *, jcl_Thread_currentThread, "java.lang.Thread_currentThread", (void)) { return jcl_current_thread(); }
BIND(void *, jcl_VirtualThread_currentThread, "java.lang.VirtualThread_currentThread", (void)) { return jcl_current_thread(); }
BIND(int, jcl_Thread_isVirtual, "java.lang.Thread_isVirtual", (void *self)) { return jcl_thread_of(self)->is_virtual; }
BIND(int64_t, jcl_Thread_threadId, "java.lang.Thread_threadId", (void *self)) { return jcl_thread_of(self)->id; }
BIND(void *, jcl_Thread_getName, "java.lang.Thread_getName", (void *self)) { return nabu_jstring_new(jcl_thread_of(self)->name); }
BIND(int, jcl_Thread_holdsLock, "java.lang.Thread_holdsLock", (void *obj)) {
    if (obj == NULL) jcl_throw_new("java/lang/NullPointerException", "java/lang/RuntimeException", NULL);
    return jcl_owns_monitor(obj);
}
BIND(void *, jcl_Thread_getContextClassLoader, "java.lang.Thread_getContextClassLoader", (void *self)) { (void)self; return NULL; }
BIND(void *, jcl_Thread_getUncaughtExceptionHandler, "java.lang.Thread_getUncaughtExceptionHandler", (void *self)) { return jcl_thread_of(self)->handler; }
/* Virtual threads run on continuations unless NABU_CONTINUATIONS=0 (and the runtime has them, see
 * JCL_CONTINUATIONS); otherwise each one is bound to a native thread of its own. */
BIND(int, jcl_ContinuationSupport_isSupported0, "jdk.internal.vm.ContinuationSupport_isSupported0", (void)) {
    return jcl_continuations_enabled();
}
static void *jcl_default_handler = NULL;
BIND(void *, jcl_Thread_getDefaultUncaughtExceptionHandler, "java.lang.Thread_getDefaultUncaughtExceptionHandler", (void)) { return __atomic_load_n(&jcl_default_handler, __ATOMIC_ACQUIRE); }
BIND(void, jcl_Thread_setDefaultUncaughtExceptionHandler, "java.lang.Thread_setDefaultUncaughtExceptionHandler", (void *handler)) { __atomic_store_n(&jcl_default_handler, handler, __ATOMIC_RELEASE); }
BIND(void *, jcl_ThreadSupport_handlerOf0, "io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime.ThreadSupport_handlerOf0", (void *thread)) { return jcl_thread_of(thread)->handler; }
BIND(void *, jcl_ThreadSupport_groupOf0, "io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime.ThreadSupport_groupOf0", (void *thread)) { return jcl_thread_of(thread)->group; }
BIND(void *, jcl_ThreadSupport_mainThread0, "io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime.ThreadSupport_mainThread0", (void)) { return jcl_main_thread(); }

/* --- jdk.internal.vm.Continuation: a stack of its own per continuation ---------------------------------------
 * A continuation runs on a fiber (Windows: CreateFiberEx / SwitchToFiber). Run switches from the stack of the
 * running thread (the carrier) to the fiber, a yield switches back. A suspended continuation can be run again by
 * any thread. Boehm GC has to find the objects that only live on these stacks:
 *  - callee-saved registers are spilled to the stack before every switch (__builtin_unwind_init);
 *  - the running fiber is the stack of its thread for the collector (GC_set_stackbottom), restored on the way back;
 *  - every stack that is parked (a suspended fiber, the stack of a thread while a fiber runs on it) is a region
 *    {sp, hi} that the root hook pushes. The regions are never freed, only reused, so the hook can read them
 *    while the world is stopped without a lock. */
#if JCL_CONTINUATIONS_PLATFORM
#define JCL_CONTINUATIONS 1
#ifndef _WIN32
#if defined(__APPLE__) && !defined(_XOPEN_SOURCE)
/* the deprecated ucontext routines of macOS are only declared with _XOPEN_SOURCE */
#define _XOPEN_SOURCE 600
#include <ucontext.h>
#undef _XOPEN_SOURCE
#else
#include <ucontext.h>
#endif
#include <sys/mman.h>
#ifndef MAP_STACK
#define MAP_STACK 0
#endif
#ifndef MAP_NORESERVE
#define MAP_NORESERVE 0
#endif
#endif

struct GC_stack_base;
extern void GC_push_all_eager(void *bottom, void *top);
extern void GC_set_push_other_roots(void (*proc)(void));
extern void (*GC_get_push_other_roots(void))(void);
extern void *GC_get_my_stackbottom(struct GC_stack_base *sb);
extern void GC_set_stackbottom(void *gc_thread_handle, const struct GC_stack_base *sb);
#ifdef NABU_GC_BOEHM
extern void *GC_malloc_uncollectable(size_t n);
extern void GC_free(void *p);
#endif

/* owned: the stack of a continuation; its collector object scans it (jcl_cont_mark), so the root hook skips it */
typedef struct jcl_region { void *volatile sp; void *hi; struct jcl_region *next_free; int owned; } jcl_region;

/* The collector scans a thread from its stack pointer up to the stack end it has recorded for the thread. Moving from one
 * stack to another changes both, one after the other, and a collection that stops the thread in between would scan
 * from the one stack up to the end of the other: across memory that is not mapped (a crash), or nothing at all (the stack
 * of the running fiber goes unscanned and its objects are freed). So no collection starts while a thread is on its way:
 * every switch disables the collector before it changes the stack end, and the code that the switch lands in enables it. */
#ifdef _WIN32
#define JCL_SWITCH_BEGIN() ((void)0)
#define JCL_SWITCH_END() ((void)0)
#else
extern void GC_disable(void);
extern void GC_enable(void);
#define JCL_SWITCH_BEGIN() GC_disable()
#define JCL_SWITCH_END() GC_enable()
#endif

typedef struct jcl_cont {
#ifdef _WIN32
    LPVOID fiber;
    LPVOID back;             /* where a yield or the end goes to: the fiber of the stack that ran it */
#else
    ucontext_t uc;           /* the registers of the continuation while it is parked */
    ucontext_t *back;        /* where a yield or the end goes to: the context of the stack that ran it */
    void *stack_map;         /* the mapping of the stack (guard page first) */
    size_t stack_map_size;
#endif
    jcl_region *region;      /* the stack of this fiber while it is parked */
    void *obj;               /* the Continuation */
    void *failure;           /* a Throwable that ended the body */
    struct jcl_cont *parent; /* the continuation that was running on this thread when this one was run */
    void *hi;                /* the base of the fiber stack */
    int state;               /* 0 new, 1 running, 2 suspended, 3 done */
    int pins;
    int monitors;            /* the monitors the thread held when the continuation was run */
    uintptr_t *capture;      /* set while a thread asks a parked continuation for its stack: where to put the frames */
    int capture_count;
} jcl_cont;

static void *(*jcl_continuation_run_fn)(void *) = NULL;
void nabu_set_continuation_run_fn(void *fn) { jcl_continuation_run_fn = (void *(*)(void *))fn; }

static jcl_region *volatile *volatile jcl_regions = NULL;
static volatile long jcl_region_count = 0;
static long jcl_region_cap = 0;
static jcl_region *jcl_region_free = NULL;
static pthread_mutex_t jcl_region_lock = PTHREAD_MUTEX_INITIALIZER;
static void (*jcl_old_push_other_roots)(void) = NULL;
static volatile int jcl_roots_hooked = 0;

static void jcl_push_regions(void) {
    jcl_region *volatile *table = jcl_regions;
    const long n = jcl_region_count;
    for (long i = 0; i < n; i++) {
        jcl_region *r = table[i];
        void *sp = r->sp;
        if (sp != NULL && !r->owned && r->hi != NULL && sp < r->hi) GC_push_all_eager(sp, r->hi);
    }
    if (jcl_old_push_other_roots != NULL) jcl_old_push_other_roots();
}

static jcl_region *jcl_region_new(void *hi) {
    pthread_mutex_lock(&jcl_region_lock);
    if (!jcl_roots_hooked) {
        jcl_old_push_other_roots = GC_get_push_other_roots();
        GC_set_push_other_roots(jcl_push_regions);
        jcl_roots_hooked = 1;
    }
    jcl_region *r = jcl_region_free;
    if (r != NULL) {
        jcl_region_free = r->next_free;
        r->hi = hi;
    } else {
        r = (jcl_region *)calloc(1, sizeof *r);
        r->hi = hi;
        if (jcl_region_count == jcl_region_cap) {
            /* a bigger table is published whole; the old one stays for a collection that is reading it */
            long cap = jcl_region_cap == 0 ? 256 : jcl_region_cap * 2;
            jcl_region **bigger = (jcl_region **)calloc((size_t)cap, sizeof(jcl_region *));
            for (long i = 0; i < jcl_region_count; i++) bigger[i] = jcl_regions[i];
            jcl_region_cap = cap;
            __atomic_store_n(&jcl_regions, (jcl_region *volatile *)bigger, __ATOMIC_RELEASE);
        }
        jcl_regions[jcl_region_count] = r;
        __atomic_add_fetch(&jcl_region_count, 1, __ATOMIC_RELEASE);
    }
    pthread_mutex_unlock(&jcl_region_lock);
    return r;
}

static void jcl_region_release(jcl_region *r) {
    r->sp = NULL;
    r->hi = NULL;
    r->owned = 0;
    pthread_mutex_lock(&jcl_region_lock);
    r->next_free = jcl_region_free;
    jcl_region_free = r;
    pthread_mutex_unlock(&jcl_region_lock);
}

#if defined(__x86_64__)
#define JCL_CURRENT_SP() ({ void *sp_; __asm__ volatile("movq %%rsp, %0" : "=r"(sp_)); sp_; })
#else
#define JCL_CURRENT_SP() ({ void *sp_; __asm__ volatile("mov %0, sp" : "=r"(sp_)); sp_; })
#endif

#ifdef _WIN32
static void *jcl_stack_hi(void) {
    void *base;
    __asm__ volatile("movq %%gs:0x8, %0" : "=r"(base));
    return base;
}
#endif

static __thread void *jcl_gc_thread = NULL;      /* the collector's handle of this thread */
static __thread jcl_region *jcl_carrier_region = NULL;
static __thread jcl_cont *jcl_running = NULL;    /* the continuation running on this thread, innermost */

#ifndef _WIN32
/* The collector scans the stack of the process's first thread up to GC_stackbottom, that of the others up to the
 * stack end it has recorded for them. */
static int jcl_on_main_thread(void) {
    static __thread int known = 0, main_thread = 0;
    if (!known) {
#ifdef __APPLE__
        main_thread = pthread_main_np() != 0;
#else
        main_thread = (pid_t)syscall(SYS_gettid) == getpid();
#endif
        known = 1;
    }
    return main_thread;
}
#endif

static void jcl_set_gc_base(void *handle, void *hi) {
    struct { void *mem_base; } sb;
    sb.mem_base = hi;
    GC_set_stackbottom(handle, (const struct GC_stack_base *)&sb);
#ifndef _WIN32
    if (jcl_on_main_thread()) GC_stackbottom = (char *)hi;
#endif
}

static void *jcl_get_gc_base(void) {
#ifndef _WIN32
    if (jcl_on_main_thread()) return (void *)GC_stackbottom;
#endif
    struct { void *mem_base; } sb;
    sb.mem_base = NULL;
    GC_get_my_stackbottom((struct GC_stack_base *)&sb);
    return sb.mem_base;
}

#ifdef _WIN32
static void WINAPI jcl_fiber_main(LPVOID param) {
    jcl_cont *k = (jcl_cont *)param;
    k->hi = jcl_stack_hi();
#else
static void jcl_fiber_main(unsigned int lo, unsigned int hi) {
    jcl_cont *k = (jcl_cont *)(((uintptr_t)hi << 32) | (uintptr_t)lo);
    k->hi = (char *)k->stack_map + k->stack_map_size;
#endif
    k->region->hi = k->hi;
    jcl_set_gc_base(jcl_gc_thread, k->hi);
    JCL_SWITCH_END();
    k->failure = jcl_continuation_run_fn != NULL ? jcl_continuation_run_fn(k->obj) : NULL;
    k->state = 3;
    k->region->sp = NULL;
    JCL_SWITCH_BEGIN();
#ifdef _WIN32
    SwitchToFiber(k->back);
#else
    setcontext(k->back);
#endif
}

/* A new continuation (0) or a suspended one (2) becomes the running one (1). A thread that captures the stack of a
 * suspended continuation holds it as running for a moment; whoever wants to run it waits for that. */
static void jcl_cont_claim(jcl_cont *k) {
    for (;;) {
        int expected = k->state == 0 ? 0 : 2;
        if (__atomic_compare_exchange_n(&k->state, &expected, 1, 0, __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) return;
        sched_yield();
    }
}

/* Runs the continuation until it yields or ends. Noinline: the registers are spilled into this frame, which is then
 * parked as a region while the fiber runs. */
static __attribute__((noinline)) void jcl_cont_switch_in(jcl_cont *k) {
    __builtin_unwind_init();
    if (jcl_gc_thread == NULL) {
        struct { void *mem_base; } sb;
        jcl_gc_thread = GC_get_my_stackbottom((struct GC_stack_base *)&sb);
    }
#ifdef _WIN32
    if (!IsThreadAFiber()) ConvertThreadToFiber(NULL);
#endif
    jcl_cont *outer = jcl_running;
    const void *prev_hi = jcl_get_gc_base();
    jcl_region *mine = outer != NULL ? outer->region : jcl_carrier_region;
    if (mine == NULL) {
        mine = jcl_carrier_region = jcl_region_new((void *)prev_hi);
    }
    mine->hi = (void *)prev_hi;
    mine->sp = JCL_CURRENT_SP();
#ifdef _WIN32
    k->back = GetCurrentFiber();
#else
    ucontext_t back_context;    /* in this frame, which is pushed as part of the region of this stack */
    k->back = &back_context;
#endif
    k->parent = outer;
    k->monitors = nabu_held_monitors();
    k->region->sp = NULL;
    JCL_SWITCH_BEGIN();
    if (k->hi != NULL) jcl_set_gc_base(jcl_gc_thread, k->hi);
    jcl_running = k;
#ifdef _WIN32
    SwitchToFiber(k->fiber);
#else
    swapcontext(&back_context, &k->uc);
#endif
    /* back on the stack of this thread (yield or end); only now may another thread run or capture it */
    if (__atomic_load_n(&k->state, __ATOMIC_ACQUIRE) == 5) __atomic_store_n(&k->state, 2, __ATOMIC_RELEASE);
    jcl_running = outer;
    jcl_set_gc_base(jcl_gc_thread, (void *)prev_hi);
    JCL_SWITCH_END();
    mine->sp = NULL;
}

/* Leaves the running continuation; returns when it is run again, possibly by another thread. */
static __attribute__((noinline)) void jcl_cont_switch_out(jcl_cont *k) {
    __builtin_unwind_init();
    __atomic_store_n(&k->state, 5, __ATOMIC_RELEASE); /* yielding: the carrier makes it 2 when the context is saved */
    k->region->sp = JCL_CURRENT_SP();
    JCL_SWITCH_BEGIN();
#ifdef _WIN32
    SwitchToFiber(k->back);
#else
    swapcontext(&k->uc, k->back);
#endif
    JCL_SWITCH_END();
}

/* A jcl_cont is collector memory of a kind of its own: when something reachable refers to it (the Continuation, through
 * its tail field; the thread that runs it, through its frames) the mark procedure also scans the parked stack. The
 * stack of a continuation that nothing refers to any more (an abandoned parked virtual thread) is therefore garbage,
 * and so is everything only that stack refers to; the finalizer gives the stack back. */
struct GC_ms_entry;
extern struct GC_ms_entry *GC_mark_and_push(void *obj, struct GC_ms_entry *top, struct GC_ms_entry *limit, void **src);
extern unsigned GC_new_proc(struct GC_ms_entry *(*proc)(uintptr_t *, struct GC_ms_entry *, struct GC_ms_entry *, uintptr_t));
extern unsigned GC_new_kind(void **free_list, uintptr_t descriptor, int add_size, int clear);
extern void **GC_new_free_list(void);
extern void *GC_malloc_kind(size_t n, int kind);
extern void GC_register_finalizer_no_order(void *obj, void (*fn)(void *, void *), void *data, void (**old_fn)(void *, void *), void **old_data);

static struct GC_ms_entry *jcl_cont_mark(uintptr_t *addr, struct GC_ms_entry *msp, struct GC_ms_entry *limit, uintptr_t env) {
    (void)env;
    jcl_cont *k = (jcl_cont *)addr;
    uintptr_t *w = addr;
    for (uintptr_t *end = addr + sizeof(jcl_cont) / sizeof(uintptr_t); w < end; w++) msp = GC_mark_and_push((void *)*w, msp, limit, (void **)w);
    jcl_region *r = k->region;
    if (r != NULL && r->sp != NULL && r->hi != NULL) {
        uintptr_t *hi = (uintptr_t *)r->hi;
        for (w = (uintptr_t *)(((uintptr_t)r->sp + 7) & ~(uintptr_t)7); w < hi; w++) msp = GC_mark_and_push((void *)*w, msp, limit, (void **)w);
    }
    return msp;
}

#ifndef _WIN32
static void jcl_stack_unreserve(void);
#endif
static int jcl_cont_kind = -1;
static void *jcl_cont_alloc(void) {
    pthread_mutex_lock(&jcl_region_lock);
    if (jcl_cont_kind < 0) {
        const unsigned proc = GC_new_proc(jcl_cont_mark);
        jcl_cont_kind = (int)GC_new_kind(GC_new_free_list(), ((uintptr_t)proc << 2) | 2 /* GC_DS_PROC */, 0, 1);
    }
    pthread_mutex_unlock(&jcl_region_lock);
    return GC_malloc_kind(sizeof(jcl_cont), jcl_cont_kind);
}

/* Gives back the stack of a continuation that is done or abandoned. */
static void jcl_cont_dispose(jcl_cont *k) {
    jcl_region *r = k->region;
    if (r == NULL) return;
    /* First hide the stack from the collector (jcl_cont_mark scans it from r->sp up), then give it back: a collection
     * that stops this thread in between must not scan a stack that is already unmapped. */
    __atomic_store_n(&r->sp, NULL, __ATOMIC_SEQ_CST);
    __atomic_store_n(&k->region, NULL, __ATOMIC_SEQ_CST);
#ifdef _WIN32
    DeleteFiber(k->fiber);
#else
    munmap(k->stack_map, k->stack_map_size);
    jcl_stack_unreserve();
#endif
    jcl_region_release(r);
}
static void jcl_cont_finalize(void *k, void *data) { (void)data; jcl_cont_dispose((jcl_cont *)k); }

static void **jcl_cont_slot(void *c) {
    intptr_t off = nabu_instance_field_offset(c, "tail");
    return off >= 0 ? (void **)((char *)c + off) : NULL;
}

#ifndef _WIN32
/* Every stack is two mappings (the stack and its guard page) and a process may have vm.max_map_count of them, 65530 by
 * default: when that is used up the collector cannot map its own memory either and the program aborts. So the stacks
 * stop at a part of the limit and the continuation that would be one too many gets an OutOfMemoryError instead. */
static long jcl_stack_count = 0;
static long jcl_stack_limit = 0;
static int jcl_stack_reserve(void) {
    if (jcl_stack_limit == 0) {
        long maps = 65530;
#ifdef __APPLE__
        /* No vm.max_map_count, but also no MAP_NORESERVE: every touched stack costs real memory, and 200000 abandoned
         * ones got a CI runner's process killed. Keep the Linux default as the bound, collecting before giving up. */
        FILE *f = NULL;
#else
        FILE *f = fopen("/proc/sys/vm/max_map_count", "r");
#endif
        if (f != NULL) {
            long value = 0;
            if (fscanf(f, "%ld", &value) == 1 && value > 0) maps = value;
            fclose(f);
        }
        const long room = maps - (maps / 16 > 2048 ? maps / 16 : 2048);  /* what the rest of the process needs */
        jcl_stack_limit = room > 2 ? room / 2 : 1;
    }
    if (__atomic_add_fetch(&jcl_stack_count, 1, __ATOMIC_SEQ_CST) > jcl_stack_limit) {
        __atomic_sub_fetch(&jcl_stack_count, 1, __ATOMIC_SEQ_CST);
        /* the stacks are not collector memory, so they do not make it collect: abandoned ones may be waiting for a cycle */
        extern void GC_gcollect(void);
        extern int GC_invoke_finalizers(void);
        GC_gcollect();
        GC_invoke_finalizers();
        if (__atomic_add_fetch(&jcl_stack_count, 1, __ATOMIC_SEQ_CST) <= jcl_stack_limit) return 1;
        __atomic_sub_fetch(&jcl_stack_count, 1, __ATOMIC_SEQ_CST);
        static int told = 0;
        if (!__atomic_exchange_n(&told, 1, __ATOMIC_SEQ_CST)) {
            fprintf(stderr, "nabu: no stack for another virtual thread: the limit of %ld stacks that the memory mappings "
                    "(vm.max_map_count) allow is reached; raise it with sysctl vm.max_map_count=262144\n",
                    jcl_stack_limit);
        }
        return 0;
    }
    return 1;
}
static void jcl_stack_unreserve(void) { __atomic_sub_fetch(&jcl_stack_count, 1, __ATOMIC_SEQ_CST); }
#endif

BIND(void, jcl_Continuation_enterSpecial, "jdk.internal.vm.Continuation_enterSpecial", (void *c, uint8_t isContinue, uint8_t isVirtual)) {
    (void)isVirtual;
    void **slot = jcl_cont_slot(c);
    jcl_cont *k;
    if (isContinue) {
        k = (jcl_cont *)*slot;
    } else {
        k = (jcl_cont *)jcl_cont_alloc();
        memset(k, 0, sizeof *k);
        k->obj = c;
        k->region = jcl_region_new(NULL);
        k->region->owned = 1;
#ifdef _WIN32
        k->fiber = CreateFiberEx(16 * 1024, (size_t)4 << 20, 0, jcl_fiber_main, k);
        const int created = k->fiber != NULL;
#else
        /* a stack of 4 MB; its pages are committed when they are used; the lowest page is a guard */
        const size_t page = (size_t)sysconf(_SC_PAGESIZE);
        k->stack_map_size = (size_t)4 << 20;
        int created = jcl_stack_reserve();
        if (created) {
            k->stack_map = mmap(NULL, k->stack_map_size, PROT_READ | PROT_WRITE,
                    MAP_PRIVATE | MAP_ANONYMOUS | MAP_NORESERVE | MAP_STACK, -1, 0);
            created = k->stack_map != MAP_FAILED;
            if (created && mprotect(k->stack_map, page, PROT_NONE) != 0) {
                munmap(k->stack_map, k->stack_map_size);
                created = 0;
            }
            if (!created) jcl_stack_unreserve();
        }
        if (created) {
            /* Known before the first switch: jcl_cont_switch_in then sets the stack end of the collector for this thread
             * before it moves to the new stack. Otherwise a collection that stops the thread in between scans from the
             * new stack up to the end of the old one, which is across memory that is not mapped. */
            k->hi = (char *)k->stack_map + k->stack_map_size;
            getcontext(&k->uc);
#ifdef __APPLE__
            /* makecontext of macOS makes the whole stack resident (4 MB each). It only needs the top of the stack to
             * start on; the stack grows down into the rest of the mapping, whose pages are used when it gets there. */
            k->uc.uc_stack.ss_sp = (char *)k->stack_map + k->stack_map_size - 2 * page;
            k->uc.uc_stack.ss_size = 2 * page;
#else
            k->uc.uc_stack.ss_sp = (char *)k->stack_map + page;
            k->uc.uc_stack.ss_size = k->stack_map_size - page;
#endif
            k->uc.uc_link = NULL;
            makecontext(&k->uc, (void (*)(void))jcl_fiber_main, 2, (unsigned int)((uintptr_t)k & 0xffffffffu),
                    (unsigned int)((uintptr_t)k >> 32));
        }
#endif
        if (!created) {
            jcl_region_release(k->region);
            k->region = NULL;
            GC_free(k);
            jcl_throw_new("java/lang/OutOfMemoryError", "java/lang/Error", "Cannot create a stack for a continuation");
            return;
        }
        *slot = k;
        GC_register_finalizer_no_order(k, jcl_cont_finalize, NULL, NULL, NULL);
    }
    jcl_cont_claim(k);
    jcl_cont_switch_in(k);
    if (k->state == 3) {
        void *failure = k->failure;
        *slot = NULL;
        GC_register_finalizer_no_order(k, NULL, NULL, NULL, NULL);
        jcl_cont_dispose(k);
        GC_free(k);
        if (failure != NULL) nabu_throw(failure);
    }
}

/* Parks the continuation. The monitors it holds are owned by the virtual thread (its id is the lock word), so they
 * stay held while it is parked; only the count of the carrier has to follow the continuation. */
static void jcl_cont_suspend(jcl_cont *k) {
    const int held = nabu_held_monitors() - k->monitors;
    nabu_set_held_monitors(k->monitors);
    jcl_cont_switch_out(k);
    nabu_set_held_monitors(k->monitors + held); /* k->monitors: the count of the thread that runs it now */
}

BIND(int, jcl_Continuation_doYield, "jdk.internal.vm.Continuation_doYield", (void)) {
    jcl_cont *k = jcl_running;
    if (k == NULL) return 3;
    if (k->pins > 0) return 2; /* CRITICAL_SECTION */
    jcl_cont_suspend(k);
    /* Resumed: either to go on, or by a thread that wants the stack of this parked continuation. */
    while (k->capture != NULL) {
        k->capture_count = nabu_capture_frames(k->capture, 128);
        k->capture = NULL;
        jcl_cont_suspend(k);
    }
    return 0;
}

/* ThreadSupport.parkedFrames0(continuation): the frames (addresses) of a suspended continuation, innermost first,
 * or null when it is not suspended (new, running, done). The stack is walked by the continuation itself: this
 * thread runs it up to the point where it was parked and it hands the frames back. */
BIND(void *, jcl_ThreadSupport_parkedFrames0, "io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime.ThreadSupport_parkedFrames0", (void *c)) {
    void **slot = jcl_cont_slot(c);
    jcl_cont *k = slot != NULL ? (jcl_cont *)*slot : NULL;
    if (k == NULL) return NULL;
    for (int spins = 0; __atomic_load_n(&k->state, __ATOMIC_ACQUIRE) == 5 && spins < 1000; spins++) sched_yield();
    int expected = 2;
    if (!__atomic_compare_exchange_n(&k->state, &expected, 1, 0, __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) return NULL;
    uintptr_t frames[128];
    k->capture = frames;
    k->capture_count = 0;
    jcl_cont_switch_in(k);
    k->capture = NULL;
    const int n = k->capture_count;
    void *arr = jcl_alloc(8 + (size_t)n * sizeof(int64_t));
    ((int32_t *)arr)[0] = n;
    ((int32_t *)arr)[1] = 8;
    nabu_array_tag(arr, "[J");
    for (int i = 0; i < n; i++) ((int64_t *)((char *)arr + 8))[i] = (int64_t)frames[i];
    return arr;
}

BIND(void, jcl_Continuation_pin, "jdk.internal.vm.Continuation_pin", (void)) {
    if (jcl_running != NULL) jcl_running->pins++;
}
BIND(void, jcl_Continuation_unpin, "jdk.internal.vm.Continuation_unpin", (void)) {
    if (jcl_running != NULL && jcl_running->pins > 0) jcl_running->pins--;
}
BIND(int, jcl_Continuation_isPinned0, "jdk.internal.vm.Continuation_isPinned0", (void *scope)) {
    (void)scope;
    return jcl_running != NULL && jcl_running->pins > 0 ? 2 : 0;
}
BIND(void, jcl_Continuation_registerNatives, "jdk.internal.vm.Continuation_registerNatives", (void)) { }
#else
void nabu_set_continuation_run_fn(void *fn) { (void)fn; }
BIND(void, jcl_Continuation_enterSpecial, "jdk.internal.vm.Continuation_enterSpecial", (void *c, uint8_t isContinue, uint8_t isVirtual)) {
    (void)c; (void)isContinue; (void)isVirtual;
    jcl_throw_new("java/lang/UnsupportedOperationException", "java/lang/RuntimeException", "Continuations need the Boehm GC on Windows or Linux");
}
BIND(int, jcl_Continuation_doYield, "jdk.internal.vm.Continuation_doYield", (void)) { return 3; }
BIND(void, jcl_Continuation_pin, "jdk.internal.vm.Continuation_pin", (void)) { }
BIND(void, jcl_Continuation_unpin, "jdk.internal.vm.Continuation_unpin", (void)) { }
BIND(int, jcl_Continuation_isPinned0, "jdk.internal.vm.Continuation_isPinned0", (void *scope)) { (void)scope; return 0; }
BIND(void, jcl_Continuation_registerNatives, "jdk.internal.vm.Continuation_registerNatives", (void)) { }
#endif

/* java.lang.VirtualThread: the JVMTI notifications are not there; the thread that unblocks virtual threads waiting
 * for a monitor has nothing to wait for (virtual threads that wait for a monitor stay on their carrier). */
BIND(void, jcl_VirtualThread_registerNatives, "java.lang.VirtualThread_registerNatives", (void)) { }
BIND(void, jcl_VirtualThread_notifyJvmtiStart, "java.lang.VirtualThread_notifyJvmtiStart", (void *self)) { (void)self; }
BIND(void, jcl_VirtualThread_notifyJvmtiEnd, "java.lang.VirtualThread_notifyJvmtiEnd", (void *self)) { (void)self; }
BIND(void, jcl_VirtualThread_notifyJvmtiMount, "java.lang.VirtualThread_notifyJvmtiMount", (void *self, uint8_t hide)) { (void)self; (void)hide; }
BIND(void, jcl_VirtualThread_notifyJvmtiUnmount, "java.lang.VirtualThread_notifyJvmtiUnmount", (void *self, uint8_t hide)) { (void)self; (void)hide; }
BIND(void, jcl_VirtualThread_notifyJvmtiDisableSuspend, "java.lang.VirtualThread_notifyJvmtiDisableSuspend", (uint8_t enter)) { (void)enter; }
BIND(void, jcl_VirtualThread_postPinnedEvent, "java.lang.VirtualThread_postPinnedEvent", (void *op)) { (void)op; }
BIND(void *, jcl_VirtualThread_takeVirtualThreadListToUnblock, "java.lang.VirtualThread_takeVirtualThreadListToUnblock", (void)) {
    nabu_waiter *waiter = nabu_waiter_self();
    for (;;) {
        const unsigned long ticket = nabu_waiter_ticket(waiter);
        nabu_waiter_wait(waiter, ticket, -1);
    }
    return NULL;
}

/* The object (made by the continuation bridge) that gives ThreadSupport the methods of the JDK's VirtualThread. */
static void *(*jcl_virtual_ops_fn)(void) = NULL;
void nabu_set_virtual_ops_fn(void *fn) { jcl_virtual_ops_fn = (void *(*)(void))fn; }

BIND(void *, jcl_ThreadSupport_virtualOps0, "io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime.ThreadSupport_virtualOps0", (void)) {
    static void *ops = NULL;
    if (!jcl_continuations_enabled() || jcl_virtual_ops_fn == NULL) return NULL;
    if (ops == NULL) ops = jcl_virtual_ops_fn();
    return ops;
}

/* Thread.inheritScopedValueBindings(container), called by VirtualThread.start: a platform thread started in a
 * container does not inherit either (see ThreadSupport.containerInherits0). */
BIND(void, jcl_Thread_inheritScopedValueBindings, "java.lang.Thread_inheritScopedValueBindings", (void *self, void *container)) { (void)self; (void)container; }

/* Thread.clearReferences(), at the end of a virtual thread: drops what the thread kept. */
/* Thread.nioBlocker(): the channel an interrupt has to close (VirtualThread.interrupt asks for it). */
BIND(void *, jcl_Thread_nioBlocker, "java.lang.Thread_nioBlocker", (void *self)) { return jcl_thread_of(self)->nio_blocker; }
BIND(void, jcl_Thread_clearReferences, "java.lang.Thread_clearReferences", (void *self)) {
    s_thread *st = jcl_thread_of(self);
    st->threadLocals = NULL;
    st->inheritableThreadLocals = NULL;
    st->target = NULL;
}
/* carrier.setInterrupt(): the interrupt of a virtual thread is passed on to its carrier. */
BIND(void, jcl_Thread_setInterrupt, "java.lang.Thread_setInterrupt", (void *self)) {
    s_thread *target = jcl_thread_of(self);
    target->interrupted = 1;
    nabu_waiter_wake(target->waiter);
}
BIND(void *, jcl_Thread_uncaughtExceptionHandler, "java.lang.Thread_uncaughtExceptionHandler", (void *self)) { return jcl_thread_of(self)->handler; }

BIND(void, jcl_Thread_setContinuation, "java.lang.Thread_setContinuation", (void *self, void *c)) { jcl_thread_of(self)->continuation = c; }
BIND(void *, jcl_Thread_getContinuation, "java.lang.Thread_getContinuation", (void *self)) { return jcl_thread_of(self)->continuation; }

static void *jcl_the_runtime(void) {
    static void *runtime = NULL;
    void *seen = __atomic_load_n(&runtime, __ATOMIC_ACQUIRE);
    if (seen != NULL) return seen;
    void *made = jcl_new_str_obj("runtime");
    void *none = NULL;
    if (__atomic_compare_exchange_n(&runtime, &none, made, 0, __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) return made;
    return none;
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
/* Whether the class (internal name) is an annotation type of the program: an object the runtime made for an
 * annotation counts as a java.lang.annotation.Annotation (nabu_instanceof asks). */
int nabu_class_is_annotation(const char *internal) {
    int32_t f = jcl_class_flags((void *)internal);
    return f >= 0 && (f & 0x2000) != 0;
}
extern int nabu_instanceof_array(void *obj, const char *wanted);
BIND(int, jcl_Class_isInstance, "java.lang.Class_isInstance", (void *self, void *obj)) {
    if (obj == NULL) return 0;
    const char *name = jcl_str(self);
    if (strcmp(name, "java/lang/Object") == 0) return 1;   /* every object, an array too */
    if (name[0] == '[') return nabu_instanceof_array(obj, name);
    if (nabu_is_jstring(obj)) {
        return strcmp(name, "java/lang/String") == 0 || strcmp(name, "java/lang/Object") == 0
                || strcmp(name, "java/lang/CharSequence") == 0;
    }
    if (!nabu_jcl_is_object(obj) && jcl_compiled_type_name(obj) == NULL) return 0;
    return nabu_instanceof(obj, name);
}
BIND(int32_t, jcl_Class_getModifiers, "java.lang.Class_getModifiers", (void *self)) {
    int32_t f = jcl_class_flags(self);
    return f < 0 ? 1 : (f & 0xffff & ~0x1000);
}
BIND(int, jcl_Class_isAnnotation, "java.lang.Class_isAnnotation", (void *self)) {
    int32_t f = jcl_class_flags(self);
    return f >= 0 && (f & 0x2000) != 0;
}
BIND(int, jcl_Class_isAnonymousClass, "java.lang.Class_isAnonymousClass", (void *self)) { (void)self; return 0; }
BIND(int, jcl_Class_isLocalClass, "java.lang.Class_isLocalClass", (void *self)) { (void)self; return 0; }
BIND(int, jcl_Class_isRecord, "java.lang.Class_isRecord", (void *self)) {
    int32_t f = jcl_class_flags(self);
    return f >= 0 && (f & 0x10000) != 0;
}
BIND(void *, jcl_Class_getClassLoader0, "java.lang.Class_getClassLoader0", (void *self)) { (void)self; return NULL; }
/* Class.toGenericString: no type parameters are recorded, so the type name (as in a message). */
BIND(void *, jcl_Class_toGenericString, "java.lang.Class_toGenericString", (void *self)) { return nabu_jstring_new(jcl_source_name(jcl_str(self), 0)); }
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
/* Reference.get() of newer JDK builds calls this native. */
BIND(void *, jcl_Reference_get0, "java.lang.ref.Reference_get0", (void *self)) {
    void **slot = jcl_referent_slot(self);
    return slot != NULL ? *slot : NULL;
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
BIND(void, jcl_PhantomReference_clear0, "java.lang.ref.PhantomReference_clear0", (void *self)) {
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
static volatile int jcl_crc_ready = 0;
static uint32_t jcl_crc_update(uint32_t crc, const unsigned char *data, int32_t len) {
    /* Built once; a thread that sees the flag sees the whole table (release and acquire). */
    if (!__atomic_load_n(&jcl_crc_ready, __ATOMIC_ACQUIRE)) {
        nabu_rt_lock();
        if (!jcl_crc_ready) {
            for (uint32_t n = 0; n < 256; n++) {
                uint32_t c = n;
                for (int k = 0; k < 8; k++) c = (c & 1) ? 0xEDB88320u ^ (c >> 1) : c >> 1;
                jcl_crc_table[n] = c;
            }
            __atomic_store_n(&jcl_crc_ready, 1, __ATOMIC_RELEASE);
        }
        nabu_rt_unlock();
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

/* java.util.zip.Inflater natives (the JDK uses zlib): a resumable inflate (RFC 1951) with the zlib wrapper (RFC 1950)
 * when the stream is not "nowrap". The state keeps the input it has not used yet and a 32 KB window, so input and
 * output can be supplied in pieces, as Inflater and InflaterInputStream do. */
typedef struct { uint16_t count[16]; uint16_t symbol[320]; } jcl_huff;
typedef struct {
    int nowrap, phase, last;
    unsigned char *in; size_t in_len, in_cap, in_pos;
    uint32_t bitbuf; int bitcnt;
    unsigned char win[32768]; uint32_t wtotal;
    uint32_t stored_left; int copy_len, copy_dist;
    jcl_huff lencode, distcode;
    uint32_t adler;
    char err[64];
} jcl_inflater;
enum { INF_HDR, INF_BLOCK, INF_STORED, INF_CODES, INF_TRAILER, INF_DONE };
typedef struct { size_t pos; uint32_t buf; int cnt; } jcl_inf_snap;

static int jcl_inf_bits(jcl_inflater *s, int n) {
    while (s->bitcnt < n) {
        if (s->in_pos >= s->in_len) return -1;
        s->bitbuf |= (uint32_t)s->in[s->in_pos++] << s->bitcnt;
        s->bitcnt += 8;
    }
    int v = (int)(s->bitbuf & ((1u << n) - 1));
    s->bitbuf >>= n;
    s->bitcnt -= n;
    return v;
}
static int jcl_inf_decode(jcl_inflater *s, const jcl_huff *h) {
    int code = 0, first = 0, index = 0;
    for (int len = 1; len <= 15; len++) {
        int bit = jcl_inf_bits(s, 1);
        if (bit < 0) return -1;
        code |= bit;
        int count = h->count[len];
        if (code - count < first) return h->symbol[index + (code - first)];
        index += count; first += count; first <<= 1; code <<= 1;
    }
    return -2;
}
/* Builds a decoding table; < 0 when the lengths are over-subscribed. */
static int jcl_inf_construct(jcl_huff *h, const short *length, int n) {
    short offs[16];
    for (int i = 0; i < 16; i++) h->count[i] = 0;
    for (int i = 0; i < n; i++) h->count[length[i]]++;
    if (h->count[0] == n) return 0;
    int left = 1;
    for (int len = 1; len <= 15; len++) { left <<= 1; left -= h->count[len]; if (left < 0) return left; }
    offs[1] = 0;
    for (int len = 1; len < 15; len++) offs[len + 1] = offs[len] + h->count[len];
    for (int i = 0; i < n; i++) if (length[i] != 0) h->symbol[offs[length[i]]++] = (uint16_t)i;
    return left;
}
static const short jcl_inf_lbase[29] = {3,4,5,6,7,8,9,10,11,13,15,17,19,23,27,31,35,43,51,59,67,83,99,115,131,163,195,227,258};
static const short jcl_inf_lext[29] = {0,0,0,0,0,0,0,0,1,1,1,1,2,2,2,2,3,3,3,3,4,4,4,4,5,5,5,5,0};
static const short jcl_inf_dbase[30] = {1,2,3,4,5,7,9,13,17,25,33,49,65,97,129,193,257,385,513,769,1025,1537,2049,3073,4097,6145,8193,12289,16385,24577};
static const short jcl_inf_dext[30] = {0,0,0,0,1,1,2,2,3,3,4,4,5,5,6,6,7,7,8,8,9,9,10,10,11,11,12,12,13,13};

static uint32_t jcl_adler32(uint32_t adler, const unsigned char *p, size_t n) {
    uint32_t a = adler & 0xffff, b = adler >> 16;
    while (n > 0) {
        size_t k = n < 5552 ? n : 5552;
        n -= k;
        while (k--) { a += *p++; b += a; }
        a %= 65521; b %= 65521;
    }
    return (b << 16) | a;
}

static int jcl_inf_fail(jcl_inflater *s, const char *msg) { snprintf(s->err, sizeof s->err, "%s", msg); return -1; }

/* 0 = tables built, 1 = needs more input, -1 = error */
static int jcl_inf_dynamic(jcl_inflater *s) {
    static const short order[19] = {16,17,18,0,8,7,9,6,10,5,11,4,12,3,13,2,14,1,15};
    short lengths[320];
    int nlen = jcl_inf_bits(s, 5), ndist = jcl_inf_bits(s, 5), ncode = jcl_inf_bits(s, 4);
    if (nlen < 0 || ndist < 0 || ncode < 0) return 1;
    nlen += 257; ndist += 1; ncode += 4;
    if (nlen > 286 || ndist > 30) return jcl_inf_fail(s, "too many length or distance symbols");
    int i;
    for (i = 0; i < ncode; i++) { int v = jcl_inf_bits(s, 3); if (v < 0) return 1; lengths[order[i]] = (short)v; }
    for (; i < 19; i++) lengths[order[i]] = 0;
    jcl_huff cl;
    if (jcl_inf_construct(&cl, lengths, 19) < 0) return jcl_inf_fail(s, "invalid code lengths set");
    int idx = 0;
    while (idx < nlen + ndist) {
        int sym = jcl_inf_decode(s, &cl);
        if (sym == -1) return 1;
        if (sym < 0) return jcl_inf_fail(s, "invalid code lengths set");
        if (sym < 16) { lengths[idx++] = (short)sym; continue; }
        int len = 0, rep;
        if (sym == 16) {
            if (idx == 0) return jcl_inf_fail(s, "invalid bit length repeat");
            len = lengths[idx - 1]; rep = jcl_inf_bits(s, 2); if (rep < 0) return 1; rep += 3;
        } else if (sym == 17) { rep = jcl_inf_bits(s, 3); if (rep < 0) return 1; rep += 3; }
        else { rep = jcl_inf_bits(s, 7); if (rep < 0) return 1; rep += 11; }
        if (idx + rep > nlen + ndist) return jcl_inf_fail(s, "invalid bit length repeat");
        while (rep--) lengths[idx++] = (short)len;
    }
    if (lengths[256] == 0) return jcl_inf_fail(s, "invalid code -- missing end-of-block");
    if (jcl_inf_construct(&s->lencode, lengths, nlen) < 0) return jcl_inf_fail(s, "invalid literal/lengths set");
    if (jcl_inf_construct(&s->distcode, lengths + nlen, ndist) < 0) return jcl_inf_fail(s, "invalid distances set");
    return 0;
}

static void jcl_inf_fixed(jcl_inflater *s) {
    short lengths[320];
    int i;
    for (i = 0; i < 144; i++) lengths[i] = 8;
    for (; i < 256; i++) lengths[i] = 9;
    for (; i < 280; i++) lengths[i] = 7;
    for (; i < 288; i++) lengths[i] = 8;
    jcl_inf_construct(&s->lencode, lengths, 288);
    for (i = 0; i < 30; i++) lengths[i] = 5;
    jcl_inf_construct(&s->distcode, lengths, 30);
}

#define JCL_INF_EMIT(b) do { unsigned char c_ = (unsigned char)(b); out[o++] = c_; s->win[s->wtotal++ & 32767] = c_; } while (0)

/* Inflates until the input or the output is used up. Returns 0 or -1 (error); *o_out = bytes written. */
static int jcl_inf_run(jcl_inflater *s, unsigned char *out, size_t outcap, size_t *o_out) {
    size_t o = 0;
    int rc = 0;
    for (;;) {
        jcl_inf_snap snap = { s->in_pos, s->bitbuf, s->bitcnt };
        if (s->phase == INF_DONE) break;
        if (s->phase == INF_HDR) {
            if (s->nowrap) { s->phase = INF_BLOCK; continue; }
            int cmf = jcl_inf_bits(s, 8), flg = jcl_inf_bits(s, 8);
            if (cmf < 0 || flg < 0) goto suspend;
            if (((cmf << 8) | flg) % 31 != 0) { rc = jcl_inf_fail(s, "incorrect header check"); break; }
            if ((cmf & 15) != 8) { rc = jcl_inf_fail(s, "unknown compression method"); break; }
            if (flg & 32) { rc = jcl_inf_fail(s, "preset dictionary not supported"); break; }
            s->phase = INF_BLOCK;
        } else if (s->phase == INF_BLOCK) {
            int last = jcl_inf_bits(s, 1), type = jcl_inf_bits(s, 2);
            if (last < 0 || type < 0) goto suspend;
            if (type == 0) {
                s->bitbuf >>= (s->bitcnt & 7); s->bitcnt -= (s->bitcnt & 7);
                int len = jcl_inf_bits(s, 16), nlen = jcl_inf_bits(s, 16);
                if (len < 0 || nlen < 0) goto suspend;
                if ((len ^ 0xffff) != nlen) { rc = jcl_inf_fail(s, "invalid stored block lengths"); break; }
                s->stored_left = (uint32_t)len; s->phase = INF_STORED;
            } else if (type == 1) { jcl_inf_fixed(s); s->phase = INF_CODES; }
            else if (type == 2) {
                int r = jcl_inf_dynamic(s);
                if (r < 0) { rc = -1; break; }
                if (r > 0) goto suspend;
                s->phase = INF_CODES;
            } else { rc = jcl_inf_fail(s, "invalid block type"); break; }
            s->last = last;
        } else if (s->phase == INF_STORED) {
            while (s->stored_left > 0 && o < outcap) {
                int b = jcl_inf_bits(s, 8);
                if (b < 0) goto done_loop;
                JCL_INF_EMIT(b); s->stored_left--;
            }
            if (s->stored_left > 0) break;
            s->phase = s->last ? INF_TRAILER : INF_BLOCK;
        } else if (s->phase == INF_CODES) {
            while (s->copy_len > 0 && o < outcap) {
                JCL_INF_EMIT(s->win[(s->wtotal - (uint32_t)s->copy_dist) & 32767]); s->copy_len--;
            }
            if (s->copy_len > 0 || o >= outcap) break;
            int sym = jcl_inf_decode(s, &s->lencode);
            if (sym == -1) goto suspend;
            if (sym < 0 || sym > 285) { rc = jcl_inf_fail(s, "invalid literal/length code"); break; }
            if (sym < 256) { JCL_INF_EMIT(sym); continue; }
            if (sym == 256) { s->phase = s->last ? INF_TRAILER : INF_BLOCK; continue; }
            sym -= 257;
            int extra = jcl_inf_bits(s, jcl_inf_lext[sym]);
            if (extra < 0) goto suspend;
            int len = jcl_inf_lbase[sym] + extra;
            int dsym = jcl_inf_decode(s, &s->distcode);
            if (dsym == -1) goto suspend;
            if (dsym < 0 || dsym >= 30) { rc = jcl_inf_fail(s, "invalid distance code"); break; }
            int dextra = jcl_inf_bits(s, jcl_inf_dext[dsym]);
            if (dextra < 0) goto suspend;
            int dist = jcl_inf_dbase[dsym] + dextra;
            if ((uint32_t)dist > s->wtotal) { rc = jcl_inf_fail(s, "invalid distance too far back"); break; }
            s->copy_len = len; s->copy_dist = dist;
        } else { /* INF_TRAILER */
            s->bitbuf >>= (s->bitcnt & 7); s->bitcnt -= (s->bitcnt & 7);
            s->in_pos -= (size_t)(s->bitcnt / 8); s->bitbuf = 0; s->bitcnt = 0;
            if (!s->nowrap) {
                if (s->in_len - s->in_pos < 4) break;
                const unsigned char *p = s->in + s->in_pos;
                s->stored_left = ((uint32_t)p[0] << 24) | ((uint32_t)p[1] << 16) | ((uint32_t)p[2] << 8) | p[3];
                s->in_pos += 4;
            }
            s->phase = INF_DONE;
        }
        continue;
    suspend:
        s->in_pos = snap.pos; s->bitbuf = snap.buf; s->bitcnt = snap.cnt;
        break;
    }
done_loop:
    *o_out = o;
    return rc;
}

static int64_t jcl_inf_call(int64_t addr, const unsigned char *in, int32_t inlen, unsigned char *out, int32_t outlen) {
    jcl_inflater *s = (jcl_inflater *)(intptr_t)addr;
    if (inlen > 0) {
        if (s->in_pos > 0) {
            memmove(s->in, s->in + s->in_pos, s->in_len - s->in_pos);
            s->in_len -= s->in_pos; s->in_pos = 0;
        }
        if (s->in_len + (size_t)inlen > s->in_cap) {
            s->in_cap = (s->in_len + (size_t)inlen) * 2;
            s->in = (unsigned char *)realloc(s->in, s->in_cap);
        }
        memcpy(s->in + s->in_len, in, (size_t)inlen);
        s->in_len += (size_t)inlen;
    }
    size_t produced = 0;
    int was_done = s->phase == INF_DONE;
    int rc = was_done ? 0 : jcl_inf_run(s, out, outlen < 0 ? 0 : (size_t)outlen, &produced);
    s->adler = jcl_adler32(s->adler, out, produced);
    if (rc == 0 && !was_done && s->phase == INF_DONE && !s->nowrap && s->stored_left != s->adler) {
        rc = jcl_inf_fail(s, "incorrect data check");
    }
    if (rc < 0) {
        nabu_throw(jcl_throw("java/util/zip/DataFormatException", "java/lang/Exception", jcl_dup(s->err)));
        return 0;
    }
    int finished = s->phase == INF_DONE;
    size_t left = s->in_len - s->in_pos;
    int32_t used = inlen; /* unused input stays inside the state: all of it counts as used */
    if (finished) used = left >= (size_t)inlen ? 0 : inlen - (int32_t)left;
    return (int64_t)((uint64_t)(used & 0x7fffffff) | ((uint64_t)(produced & 0x7fffffff) << 31)
            | ((uint64_t)finished << 62));
}

BIND(int64_t, jcl_Inflater_init, "java.util.zip.Inflater_init", (int32_t nowrap)) {
    jcl_inflater *s = (jcl_inflater *)calloc(1, sizeof *s);
    s->nowrap = (nowrap & 0xff) != 0;
    s->phase = INF_HDR;
    s->adler = 1;
    return (int64_t)(intptr_t)s;
}
static void jcl_inf_reset(int64_t addr) {
    jcl_inflater *s = (jcl_inflater *)(intptr_t)addr;
    unsigned char *in = s->in;
    size_t cap = s->in_cap;
    int nowrap = s->nowrap;
    memset(s, 0, sizeof *s);
    s->in = in; s->in_cap = cap; s->nowrap = nowrap;
    s->adler = 1; s->phase = INF_HDR;
}
BIND(void, jcl_Inflater_reset, "java.util.zip.Inflater_reset$I64", (int64_t addr)) { jcl_inf_reset(addr); }
static void jcl_inf_end(int64_t addr) {
    jcl_inflater *s = (jcl_inflater *)(intptr_t)addr;
    if (s == NULL) return;
    free(s->in);
    free(s);
}
/* The static native end(long) is called through InflaterSupport (RuntimeCalls redirect): its own symbol "Inflater_end"
 * is the one of the Java method end() too, and a C definition of it replaced that method. */
BIND(void, jcl_Inflater_end3, "io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime.InflaterSupport_end", (int64_t addr)) { jcl_inf_end(addr); }
BIND(void, jcl_Inflater_end2, "java.util.zip.Inflater_end$I64", (int64_t addr)) { jcl_inf_end(addr); }
BIND(int32_t, jcl_Inflater_getAdler2, "java.util.zip.Inflater_getAdler$I64", (int64_t addr)) {
    return (int32_t)((jcl_inflater *)(intptr_t)addr)->adler;
}
BIND(int64_t, jcl_Inflater_bytesBytes, "java.util.zip.Inflater_inflateBytesBytes",
     (int64_t addr, void *in, int32_t inOff, int32_t inLen, void *out, int32_t outOff, int32_t outLen)) {
    return jcl_inf_call(addr, (const unsigned char *)in + 8 + inOff, inLen, (unsigned char *)out + 8 + outOff, outLen);
}
BIND(int64_t, jcl_Inflater_bytesBuffer, "java.util.zip.Inflater_inflateBytesBuffer",
     (int64_t addr, void *in, int32_t inOff, int32_t inLen, int64_t outAddr, int32_t outLen)) {
    return jcl_inf_call(addr, (const unsigned char *)in + 8 + inOff, inLen, (unsigned char *)(intptr_t)outAddr, outLen);
}
BIND(int64_t, jcl_Inflater_bufferBytes, "java.util.zip.Inflater_inflateBufferBytes",
     (int64_t addr, int64_t inAddr, int32_t inLen, void *out, int32_t outOff, int32_t outLen)) {
    return jcl_inf_call(addr, (const unsigned char *)(intptr_t)inAddr, inLen, (unsigned char *)out + 8 + outOff, outLen);
}
BIND(int64_t, jcl_Inflater_bufferBuffer, "java.util.zip.Inflater_inflateBufferBuffer",
     (int64_t addr, int64_t inAddr, int32_t inLen, int64_t outAddr, int32_t outLen)) {
    return jcl_inf_call(addr, (const unsigned char *)(intptr_t)inAddr, inLen, (unsigned char *)(intptr_t)outAddr, outLen);
}

/* ---- java.lang.Class: nesting, sealing, synthetic (what Spring Boot's binder and bean metadata ask) ---- */

/* The outer class of a nested one ("a/B$C" -> "a/B"); NULL for a top-level class, an array and a primitive. */
static const char *jcl_outer_name(const char *n) {
    if (n[0] == '[') return NULL;
    const char *dollar = strrchr(n, '$');
    if (dollar == NULL || dollar == n || dollar[1] == 0) return NULL;
    size_t len = (size_t)(dollar - n);
    char *copy = (char *)jcl_alloc(len + 1);
    memcpy(copy, n, len);
    copy[len] = 0;
    return copy;
}
/* An anonymous ("B$1") or local ("B$1Local") class starts its simple name with a digit. */
static int jcl_is_member_name(const char *n) {
    const char *dollar = strrchr(n, '$');
    return jcl_outer_name(n) != NULL && !(dollar[1] >= '0' && dollar[1] <= '9');
}
BIND(int, jcl_java_lang_Class_isMemberClass, "java.lang.Class_isMemberClass", (void *self)) {
    return jcl_is_member_name(jcl_str(self));
}
BIND(void *, jcl_java_lang_Class_getDeclaringClass, "java.lang.Class_getDeclaringClass", (void *self)) {
    const char *n = jcl_str(self);
    return jcl_is_member_name(n) ? jcl_class(jcl_outer_name(n)) : NULL;
}
BIND(void *, jcl_java_lang_Class_getEnclosingClass, "java.lang.Class_getEnclosingClass", (void *self)) {
    const char *outer = jcl_outer_name(jcl_str(self));
    return outer != NULL ? jcl_class(outer) : NULL;
}
BIND(void *, jcl_java_lang_Class_getEnclosingMethod, "java.lang.Class_getEnclosingMethod", (void *self)) { (void)self; return NULL; }
BIND(void *, jcl_java_lang_Class_getEnclosingConstructor, "java.lang.Class_getEnclosingConstructor", (void *self)) { (void)self; return NULL; }
BIND(int, jcl_java_lang_Class_isSynthetic, "java.lang.Class_isSynthetic", (void *self)) {
    int32_t f = jcl_class_flags(self);
    return f >= 0 && (f & 0x1000) != 0;
}
BIND(int, jcl_java_lang_Class_isSealed, "java.lang.Class_isSealed", (void *self)) { (void)self; return 0; }
BIND(void *, jcl_java_lang_Class_getPermittedSubclasses, "java.lang.Class_getPermittedSubclasses", (void *self)) { (void)self; return NULL; }
BIND(void *, jcl_java_lang_Class_getRecordComponents, "java.lang.Class_getRecordComponents", (void *self)) { (void)self; return NULL; }
BIND(void *, jcl_java_lang_Class_getProtectionDomain, "java.lang.Class_getProtectionDomain", (void *self)) { (void)self; return NULL; }
BIND(void *, jcl_java_lang_Class_getSigners, "java.lang.Class_getSigners", (void *self)) { (void)self; return NULL; }
BIND(void *, jcl_java_lang_Class_asSubclass, "java.lang.Class_asSubclass", (void *self, void *other)) { (void)other; return self; }
extern int nabu_reflection_table_size(void);
extern const char *nabu_reflection_table_name(int i);
extern const char *nabu_compiled_type_name_at(int32_t i);
/* The names of the classes of the program (compiled classes and reflection entries) that start with prefix and,
 * with direct_only, have no further '$' after it; without duplicates. */
static int jcl_nested_names(const char *prefix, int direct_only, const char ***out) {
    size_t plen = strlen(prefix);
    int cap = 16, n = 0;
    const char **names = (const char **)jcl_alloc((size_t)cap * sizeof(char *));
    for (int pass = 0; pass < 2; pass++) {
        int total = pass == 0 ? (int)nabu_compiled_type_count_for_trace() : nabu_reflection_table_size();
        for (int i = 0; i < total; i++) {
            const char *name = pass == 0 ? nabu_compiled_type_name_at(i) : nabu_reflection_table_name(i);
            if (name == NULL || strncmp(name, prefix, plen) != 0 || name[plen] != '$' || name[plen + 1] == 0) continue;
            if (name[plen + 1] >= '0' && name[plen + 1] <= '9') continue;
            if (direct_only && strchr(name + plen + 1, '$') != NULL) continue;
            int dup = 0;
            for (int k = 0; k < n && !dup; k++) dup = strcmp(names[k], name) == 0;
            if (dup) continue;
            if (n == cap) {
                const char **bigger = (const char **)jcl_alloc((size_t)cap * 2 * sizeof(char *));
                memcpy(bigger, names, (size_t)n * sizeof(char *));
                names = bigger;
                cap *= 2;
            }
            names[n++] = name;
        }
    }
    *out = names;
    return n;
}
BIND(void *, jcl_java_lang_Class_getDeclaredClasses, "java.lang.Class_getDeclaredClasses", (void *self)) {
    const char **names;
    int n = jcl_nested_names(jcl_str(self), 1, &names);
    void *arr = jcl_arr_typed(n, "[Ljava/lang/Class;");
    for (int i = 0; i < n; i++) jcl_arr_set(arr, i, jcl_class(names[i]));
    return arr;
}
BIND(void *, jcl_java_lang_Class_getNestMembers, "java.lang.Class_getNestMembers", (void *self)) {
    const char *n = jcl_str(self);
    const char *dollar = strchr(n, '$');
    size_t hostLen = dollar != NULL && dollar != n ? (size_t)(dollar - n) : strlen(n);
    char *host = (char *)jcl_alloc(hostLen + 1);
    memcpy(host, n, hostLen);
    host[hostLen] = 0;
    const char **names;
    int count = jcl_nested_names(host, 0, &names);
    void *arr = jcl_arr_typed(count + 1, "[Ljava/lang/Class;");
    jcl_arr_set(arr, 0, jcl_class(host));
    for (int i = 0; i < count; i++) jcl_arr_set(arr, i + 1, jcl_class(names[i]));
    return arr;
}
BIND(void *, jcl_java_lang_Class_getAnnotationsByType, "java.lang.Class_getAnnotationsByType", (void *self, void *ann)) {
    void *found = jcl_java_lang_Class_getAnnotation(self, ann);
    void *arr = jcl_arr_typed(found != NULL ? 1 : 0, "[Ljava/lang/annotation/Annotation;");
    if (found != NULL) jcl_arr_set(arr, 0, found);
    return arr;
}
BIND(void *, jcl_java_lang_Class_toString, "java.lang.Class_toString", (void *self)) {
    const char *n = jcl_str(self);
    char *dotted = jcl_dup(n);
    for (char *c = dotted; *c; c++) if (*c == '/') *c = '.';
    int primitive = strchr(n, '/') == NULL && n[0] != '[' && strchr(n, '.') == NULL
            && (strcmp(n, "int") == 0 || strcmp(n, "long") == 0 || strcmp(n, "boolean") == 0 || strcmp(n, "double") == 0
                || strcmp(n, "float") == 0 || strcmp(n, "short") == 0 || strcmp(n, "byte") == 0
                || strcmp(n, "char") == 0 || strcmp(n, "void") == 0);
    if (primitive) return nabu_jstring_new(dotted);
    return nabu_jstring_new(jcl_concat(jcl_java_lang_Class_isInterface(self) ? "interface " : "class ", dotted));
}

/* ===========================================================================
 * Sockets: java.net.InetAddress & co, sun.nio.ch.Net, SocketDispatcher, the pipe of IOUtil and WEPoll (the selector
 * of the Windows JDK; here an epoll emulation on poll). IPv4 only. A socket is the int in the `fd` slot of a
 * FileDescriptor. Values that are Java objects (an InetAddress, an InetSocketAddress) are made by NetSupport.java.
 * =========================================================================== */
#ifdef _WIN32
typedef SOCKET jcl_sock;
#define JCL_SOCK_FAIL INVALID_SOCKET
#define JCL_SOCK_CLOSE(s) closesocket(s)
#define JCL_SOCK_ERRNO() WSAGetLastError()
#define JCL_E_WOULDBLOCK WSAEWOULDBLOCK
#define JCL_E_INPROGRESS WSAEWOULDBLOCK
#define JCL_E_INTR WSAEINTR
#define JCL_E_ADDRINUSE WSAEADDRINUSE
#define JCL_E_CONNREFUSED WSAECONNREFUSED
#define JCL_E_CONNRESET WSAECONNRESET
#define JCL_E_CONNABORTED WSAECONNABORTED
#define JCL_E_NETUNREACH WSAENETUNREACH
#define JCL_E_HOSTUNREACH WSAEHOSTUNREACH
#define JCL_POLL(fds, n, ms) WSAPoll((fds), (ULONG)(n), (ms))
typedef WSAPOLLFD jcl_pollfd;
typedef int jcl_socklen;
#else
typedef int jcl_sock;
#define JCL_SOCK_FAIL (-1)
#define JCL_SOCK_CLOSE(s) close(s)
#define JCL_SOCK_ERRNO() errno
#define JCL_E_WOULDBLOCK EWOULDBLOCK
#define JCL_E_INPROGRESS EINPROGRESS
#define JCL_E_INTR EINTR
#define JCL_E_ADDRINUSE EADDRINUSE
#define JCL_E_CONNREFUSED ECONNREFUSED
#define JCL_E_CONNRESET ECONNRESET
#define JCL_E_CONNABORTED ECONNABORTED
#define JCL_E_NETUNREACH ENETUNREACH
#define JCL_E_HOSTUNREACH EHOSTUNREACH
#define JCL_POLL(fds, n, ms) poll((fds), (nfds_t)(n), (ms))
typedef struct pollfd jcl_pollfd;
typedef socklen_t jcl_socklen;
#endif

/* Winsock has to be started once; a POSIX system needs nothing (SIGPIPE is ignored here, send reports EPIPE). */
static pthread_once_t jcl_net_once = PTHREAD_ONCE_INIT;
static void jcl_net_startup_once(void) {
#ifdef _WIN32
    WSADATA data;
    WSAStartup(MAKEWORD(2, 2), &data);
#else
    signal(SIGPIPE, SIG_IGN);
#endif
}
static void jcl_net_startup(void) { pthread_once(&jcl_net_once, jcl_net_startup_once); }

/* The blocking calls give up the global lock where there is one (POSIX); on Windows the threads run in parallel. */
#ifdef _WIN32
#define JCL_NET_BLOCK_BEGIN() 0
#define JCL_NET_BLOCK_END(r) ((void)(r))
#else
#define JCL_NET_BLOCK_BEGIN() jcl_blocking_begin()
#define JCL_NET_BLOCK_END(r) jcl_blocking_end(r)
#endif

#define JCL_IOS_UNAVAILABLE (-2)
#define JCL_IOS_INTERRUPTED (-3)
#define JCL_IOS_EOF (-1)

/* The events, the same bits as the epoll constants of WEPoll.java: Net.pollinValue() & co answer these. */
#define JCL_EV_IN 1
#define JCL_EV_OUT 4
#define JCL_EV_ERR 8
#define JCL_EV_HUP 16
#define JCL_EV_NVAL 32
#define JCL_EV_ONESHOT ((uint32_t)1 << 31)

static short jcl_ev_to_sys(uint32_t ev) {
    short s = 0;
    if (ev & JCL_EV_IN) s |= POLLIN;
    if (ev & JCL_EV_OUT) s |= POLLOUT;
    return s;
}
static uint32_t jcl_ev_from_sys(short r) {
    uint32_t e = 0;
    if (r & POLLIN) e |= JCL_EV_IN;
    if (r & POLLOUT) e |= JCL_EV_OUT;
    if (r & POLLERR) e |= JCL_EV_ERR;
    if (r & POLLHUP) e |= JCL_EV_HUP;
    if (r & POLLNVAL) e |= JCL_EV_NVAL;
    return e;
}

static const char *jcl_net_reason(int err) {
    if (err == JCL_E_ADDRINUSE) return "Address already in use";
    if (err == JCL_E_CONNREFUSED) return "Connection refused";
    if (err == JCL_E_CONNRESET) return "Connection reset";
    if (err == JCL_E_CONNABORTED) return "Software caused connection abort";
    if (err == JCL_E_NETUNREACH) return "Network is unreachable";
    if (err == JCL_E_HOSTUNREACH) return "No route to host";
    return NULL;
}
static void jcl_net_fail(const char *cls, const char *super, const char *what, int err) {
    char msg[200];
    const char *reason = jcl_net_reason(err);
    if (reason != NULL) snprintf(msg, sizeof msg, "%s: %s", reason, what);
    else snprintf(msg, sizeof msg, "%s failed (error %d)", what, err);
    nabu_throw(jcl_throw(cls, super, jcl_dup(msg)));
}
static void jcl_net_socket_fail(const char *what, int err) {
    jcl_net_fail("java/net/SocketException", "java/io/IOException", what, err);
}

static jcl_sock jcl_sock_of(void *fdObj) {
    const int fd = jcl_chan_fd(fdObj);
    return fd < 0 ? JCL_SOCK_FAIL : (jcl_sock)fd;
}

/* The IPv4 address (host byte order) of an InetAddress: InetAddress.holder.address. */
static int jcl_inet_ipv4(void *inetAddress, uint32_t *out) {
    if (inetAddress == NULL) return 0;
    const intptr_t holderOffset = nabu_instance_field_offset(inetAddress, "holder");
    if (holderOffset < 0) return 0;
    void *holder = *(void **)((char *)inetAddress + holderOffset);
    if (holder == NULL) return 0;
    const intptr_t addressOffset = nabu_instance_field_offset(holder, "address");
    if (addressOffset < 0) return 0;
    *out = (uint32_t)*(int32_t *)((char *)holder + addressOffset);
    return 1;
}

static void jcl_sockaddr_in(struct sockaddr_in *sa, uint32_t hostOrderAddress, int port) {
    memset(sa, 0, sizeof *sa);
    sa->sin_family = AF_INET;
    sa->sin_port = htons((unsigned short)port);
    sa->sin_addr.s_addr = htonl(hostOrderAddress);
}

static void jcl_sock_set_blocking(jcl_sock s, int blocking) {
#ifdef _WIN32
    u_long mode = blocking ? 0 : 1;
    ioctlsocket(s, FIONBIO, &mode);
#else
    int flags = fcntl(s, F_GETFL, 0);
    if (flags >= 0) fcntl(s, F_SETFL, blocking ? (flags & ~O_NONBLOCK) : (flags | O_NONBLOCK));
#endif
}

/* ---- java.net.InetAddress and friends: no IPv6, no interface list ---- */
BIND(void, jcl_InetAddress_init, "java.net.InetAddress_init", (void)) { jcl_net_startup(); }
BIND(void, jcl_Inet4Address_init, "java.net.Inet4Address_init", (void)) { }
BIND(void, jcl_Inet6Address_init, "java.net.Inet6Address_init", (void)) { }
BIND(uint8_t, jcl_InetAddress_isIPv4Available, "java.net.InetAddress_isIPv4Available", (void)) { return 1; }
BIND(uint8_t, jcl_InetAddress_isIPv6Supported, "java.net.InetAddress_isIPv6Supported", (void)) { return 0; }
BIND(void *, jcl_Inet4AddressImpl_getLocalHostName, "java.net.Inet4AddressImpl_getLocalHostName", (void *self)) {
    (void)self;
    jcl_net_startup();
    char name[256];
    if (gethostname(name, sizeof name) != 0 || name[0] == 0) snprintf(name, sizeof name, "localhost");
    return nabu_jstring_new(name);
}
BIND(void *, jcl_Inet4AddressImpl_getHostByAddr, "java.net.Inet4AddressImpl_getHostByAddr", (void *self, void *addr)) {
    (void)self; (void)addr;
    nabu_throw(jcl_throw("java/net/UnknownHostException", "java/io/IOException", "no host name for the address"));
    return NULL;
}
BIND(void *, jcl_Inet6AddressImpl_getLocalHostName, "java.net.Inet6AddressImpl_getLocalHostName", (void *self)) {
    return jcl_Inet4AddressImpl_getLocalHostName(self);
}
BIND(void *, jcl_Inet6AddressImpl_getHostByAddr, "java.net.Inet6AddressImpl_getHostByAddr", (void *self, void *addr)) {
    return jcl_Inet4AddressImpl_getHostByAddr(self, addr);
}
BIND(void, jcl_NetworkInterface_init, "java.net.NetworkInterface_init", (void)) { }
BIND(void *, jcl_NetworkInterface_getAll, "java.net.NetworkInterface_getAll", (void)) {
    return jcl_arr_typed(0, "[Ljava/net/NetworkInterface;");
}
BIND(void *, jcl_NetworkInterface_getByName0, "java.net.NetworkInterface_getByName0", (void *name)) { (void)name; return NULL; }
BIND(void *, jcl_NetworkInterface_getByIndex0, "java.net.NetworkInterface_getByIndex0", (int32_t index)) { (void)index; return NULL; }
BIND(void *, jcl_NetworkInterface_getByInetAddress0, "java.net.NetworkInterface_getByInetAddress0", (void *addr)) { (void)addr; return NULL; }
BIND(uint8_t, jcl_NetworkInterface_boundInetAddress0, "java.net.NetworkInterface_boundInetAddress0", (void *addr)) { (void)addr; return 0; }

/* ---- NetSupport (the runtime package): what needs more than primitives is done in Java on top of these ---- */
#define JCL_NS(n) "io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime.NetSupport_" n
BIND(int32_t, jcl_NetSupport_fdVal0, JCL_NS("fdVal0"), (void *fdObj)) { return jcl_chan_fd(fdObj); }
BIND(void, jcl_NetSupport_setFdVal0, JCL_NS("setFdVal0"), (void *fdObj, int32_t fd)) {
    int32_t *slot = jcl_fd_slot(fdObj);
    if (slot != NULL) *slot = fd;
}
/* The new socket of an accept (the listening socket is the int), IOStatus.UNAVAILABLE when none is waiting. */
BIND(int32_t, jcl_NetSupport_accept0, JCL_NS("accept0"), (int32_t listening)) {
    struct sockaddr_in sa;
    jcl_socklen length = sizeof sa;
    const int released = JCL_NET_BLOCK_BEGIN();
    const jcl_sock s = accept((jcl_sock)listening, (struct sockaddr *)&sa, &length);
    const int err = s == JCL_SOCK_FAIL ? JCL_SOCK_ERRNO() : 0;
    JCL_NET_BLOCK_END(released);
    if (s == JCL_SOCK_FAIL) {
        if (err == JCL_E_WOULDBLOCK) return JCL_IOS_UNAVAILABLE;
        if (err == JCL_E_INTR) return JCL_IOS_INTERRUPTED;
        jcl_net_socket_fail("Accept", err);
        return -1;
    }
    return (int32_t)s;
}
/* (IPv4 address << 16) | port of the local or the remote end; -1 when there is none. */
BIND(int64_t, jcl_NetSupport_name0, JCL_NS("name0"), (int32_t fd, uint8_t remote)) {
    struct sockaddr_in sa;
    jcl_socklen length = sizeof sa;
    const int r = remote ? getpeername((jcl_sock)fd, (struct sockaddr *)&sa, &length)
                         : getsockname((jcl_sock)fd, (struct sockaddr *)&sa, &length);
    if (r != 0) return -1;
    return ((int64_t)ntohl(sa.sin_addr.s_addr) << 16) | (int64_t)ntohs(sa.sin_port);
}
/* The IPv4 addresses of a host name, 4 bytes each; null when the name is unknown. */
BIND(void *, jcl_NetSupport_resolve0, JCL_NS("resolve0"), (void *host)) {
    jcl_net_startup();
    struct addrinfo hints, *result = NULL;
    memset(&hints, 0, sizeof hints);
    hints.ai_family = AF_INET;
    hints.ai_socktype = SOCK_STREAM;
    if (host == NULL || getaddrinfo(jcl_str(host), NULL, &hints, &result) != 0 || result == NULL) return NULL;
    char bytes[4 * 16];
    size_t n = 0;
    for (struct addrinfo *a = result; a != NULL && n + 4 <= sizeof bytes; a = a->ai_next) {
        if (a->ai_family != AF_INET) continue;
        memcpy(bytes + n, &((struct sockaddr_in *)a->ai_addr)->sin_addr, 4);
        n += 4;
    }
    freeaddrinfo(result);
    if (n == 0) return NULL;
    void *array = jcl_alloc(8 + n);
    ((int32_t *)array)[0] = (int32_t)n;
    ((int32_t *)array)[1] = 1;
    jcl_arr_set_type(array, "[B");
    memcpy((char *)array + 8, bytes, n);
    return array;
}

/* ---- sun.nio.ch.Net ---- */
BIND(uint8_t, jcl_Net_isIPv6Available0, "sun.nio.ch.Net_isIPv6Available0", (void)) { return 0; }
BIND(uint8_t, jcl_Net_isReusePortAvailable0, "sun.nio.ch.Net_isReusePortAvailable0", (void)) { return 0; }
BIND(int32_t, jcl_Net_isExclusiveBindAvailable, "sun.nio.ch.Net_isExclusiveBindAvailable", (void)) { return -1; }
BIND(uint8_t, jcl_Net_shouldShutdownWriteBeforeClose0, "sun.nio.ch.Net_shouldShutdownWriteBeforeClose0", (void)) { return 1; }
BIND(uint8_t, jcl_Net_shouldSetBothIPv4AndIPv6Options0, "sun.nio.ch.Net_shouldSetBothIPv4AndIPv6Options0", (void)) { return 0; }
BIND(uint8_t, jcl_Net_canIPv6SocketJoinIPv4Group0, "sun.nio.ch.Net_canIPv6SocketJoinIPv4Group0", (void)) { return 0; }
BIND(uint8_t, jcl_Net_canJoin6WithIPv4Group0, "sun.nio.ch.Net_canJoin6WithIPv4Group0", (void)) { return 0; }
BIND(uint8_t, jcl_Net_canUseIPv6OptionsWithIPv4LocalAddress0, "sun.nio.ch.Net_canUseIPv6OptionsWithIPv4LocalAddress0", (void)) { return 0; }
BIND(int16_t, jcl_Net_pollinValue, "sun.nio.ch.Net_pollinValue", (void)) { return JCL_EV_IN; }
BIND(int16_t, jcl_Net_polloutValue, "sun.nio.ch.Net_polloutValue", (void)) { return JCL_EV_OUT; }
BIND(int16_t, jcl_Net_pollerrValue, "sun.nio.ch.Net_pollerrValue", (void)) { return JCL_EV_ERR; }
BIND(int16_t, jcl_Net_pollhupValue, "sun.nio.ch.Net_pollhupValue", (void)) { return JCL_EV_HUP; }
BIND(int16_t, jcl_Net_pollnvalValue, "sun.nio.ch.Net_pollnvalValue", (void)) { return JCL_EV_NVAL; }
BIND(int16_t, jcl_Net_pollconnValue, "sun.nio.ch.Net_pollconnValue", (void)) { return JCL_EV_OUT; }

BIND(int32_t, jcl_Net_socket0, "sun.nio.ch.Net_socket0", (uint8_t preferIPv6, uint8_t stream, uint8_t reuse, uint8_t fastLoopback)) {
    (void)preferIPv6; (void)fastLoopback;
    jcl_net_startup();
    const jcl_sock s = socket(AF_INET, stream ? SOCK_STREAM : SOCK_DGRAM, 0);
    if (s == JCL_SOCK_FAIL) {
        jcl_net_socket_fail("Socket creation", JCL_SOCK_ERRNO());
        return -1;
    }
#ifndef _WIN32
    if (reuse) {
        int one = 1;
        setsockopt(s, SOL_SOCKET, SO_REUSEADDR, (const char *)&one, sizeof one);
    }
#else
    (void)reuse;   /* SO_REUSEADDR on Windows lets a second socket take the port */
#endif
    return (int32_t)s;
}

BIND(void, jcl_Net_bind0, "sun.nio.ch.Net_bind0", (void *fdObj, uint8_t preferIPv6, uint8_t useExclBind, void *inetAddress, int32_t port)) {
    (void)preferIPv6; (void)useExclBind;
    uint32_t address = 0;
    if (!jcl_inet_ipv4(inetAddress, &address)) {
        jcl_net_socket_fail("Bind (an address that is not IPv4)", 0);
        return;
    }
    struct sockaddr_in sa;
    jcl_sockaddr_in(&sa, address, port);
    if (bind(jcl_sock_of(fdObj), (struct sockaddr *)&sa, sizeof sa) != 0) {
        const int err = JCL_SOCK_ERRNO();
        jcl_net_fail("java/net/BindException", "java/net/SocketException", "bind", err);
    }
}

BIND(void, jcl_Net_listen, "sun.nio.ch.Net_listen", (void *fdObj, int32_t backlog)) {
    if (listen(jcl_sock_of(fdObj), backlog) != 0) jcl_net_socket_fail("Listen", JCL_SOCK_ERRNO());
}

BIND(int32_t, jcl_Net_connect0, "sun.nio.ch.Net_connect0", (uint8_t preferIPv6, void *fdObj, void *inetAddress, int32_t port)) {
    (void)preferIPv6;
    uint32_t address = 0;
    if (!jcl_inet_ipv4(inetAddress, &address)) {
        jcl_net_socket_fail("Connect (an address that is not IPv4)", 0);
        return -1;
    }
    struct sockaddr_in sa;
    jcl_sockaddr_in(&sa, address, port);
    const int released = JCL_NET_BLOCK_BEGIN();
    const int r = connect(jcl_sock_of(fdObj), (struct sockaddr *)&sa, sizeof sa);
    const int err = r != 0 ? JCL_SOCK_ERRNO() : 0;
    JCL_NET_BLOCK_END(released);
    if (r == 0) return 1;
    if (err == JCL_E_INPROGRESS || err == JCL_E_WOULDBLOCK) return JCL_IOS_UNAVAILABLE;
    if (err == JCL_E_INTR) return JCL_IOS_INTERRUPTED;
    jcl_net_fail("java/net/ConnectException", "java/net/SocketException", "connect", err);
    return -1;
}

BIND(void, jcl_Net_shutdown, "sun.nio.ch.Net_shutdown", (void *fdObj, int32_t how)) {
    if (shutdown(jcl_sock_of(fdObj), how) != 0) {
        const int err = JCL_SOCK_ERRNO();
        if (err != 10057 /* WSAENOTCONN */ && err != 107 /* ENOTCONN */) jcl_net_socket_fail("Shutdown", err);
    }
}

BIND(int32_t, jcl_Net_localPort, "sun.nio.ch.Net_localPort", (void *fdObj)) {
    struct sockaddr_in sa;
    jcl_socklen length = sizeof sa;
    if (getsockname(jcl_sock_of(fdObj), (struct sockaddr *)&sa, &length) != 0) {
        jcl_net_socket_fail("getsockname", JCL_SOCK_ERRNO());
        return -1;
    }
    return ntohs(sa.sin_port);
}
BIND(int32_t, jcl_Net_remotePort, "sun.nio.ch.Net_remotePort", (void *fdObj)) {
    struct sockaddr_in sa;
    jcl_socklen length = sizeof sa;
    if (getpeername(jcl_sock_of(fdObj), (struct sockaddr *)&sa, &length) != 0) {
        jcl_net_socket_fail("getpeername", JCL_SOCK_ERRNO());
        return -1;
    }
    return ntohs(sa.sin_port);
}

BIND(int32_t, jcl_Net_getIntOption0, "sun.nio.ch.Net_getIntOption0", (void *fdObj, uint8_t mayNeedConversion, int32_t level, int32_t option)) {
    (void)mayNeedConversion;
    const jcl_sock s = jcl_sock_of(fdObj);
    if (level == SOL_SOCKET && option == SO_LINGER) {
        struct linger linger;
        jcl_socklen length = sizeof linger;
        if (getsockopt(s, level, option, (char *)&linger, &length) != 0) {
            jcl_net_socket_fail("getsockopt", JCL_SOCK_ERRNO());
            return 0;
        }
        return linger.l_onoff ? (int32_t)linger.l_linger : -1;
    }
    int value = 0;
    jcl_socklen length = sizeof value;
    if (getsockopt(s, level, option, (char *)&value, &length) != 0) {
        jcl_net_socket_fail("getsockopt", JCL_SOCK_ERRNO());
        return 0;
    }
    return value;
}
BIND(void, jcl_Net_setIntOption0, "sun.nio.ch.Net_setIntOption0", (void *fdObj, uint8_t mayNeedConversion, int32_t level, int32_t option, int32_t arg, uint8_t isIPv6)) {
    (void)mayNeedConversion; (void)isIPv6;
    const jcl_sock s = jcl_sock_of(fdObj);
    int r;
    if (level == SOL_SOCKET && option == SO_LINGER) {
        struct linger linger;
        linger.l_onoff = arg >= 0;
        linger.l_linger = (unsigned short)(arg >= 0 ? arg : 0);
        r = setsockopt(s, level, option, (const char *)&linger, sizeof linger);
    } else {
        int value = arg;
        r = setsockopt(s, level, option, (const char *)&value, sizeof value);
    }
    if (r != 0) jcl_net_socket_fail("setsockopt", JCL_SOCK_ERRNO());
}

/* Waits for the events (JCL_EV_*) on one socket; the events that happened, 0 on a time-out. */
BIND(int32_t, jcl_Net_poll, "sun.nio.ch.Net_poll", (void *fdObj, int32_t events, int64_t timeout)) {
    jcl_pollfd p;
    p.fd = jcl_sock_of(fdObj);
    p.events = jcl_ev_to_sys((uint32_t)events);
    p.revents = 0;
    const int released = JCL_NET_BLOCK_BEGIN();
    const int r = JCL_POLL(&p, 1, (int)(timeout > 2147483647 ? 2147483647 : timeout));
    JCL_NET_BLOCK_END(released);
    if (r < 0) {
        jcl_net_socket_fail("poll", JCL_SOCK_ERRNO());
        return 0;
    }
    return r == 0 ? 0 : (int32_t)jcl_ev_from_sys(p.revents);
}
/* Whether a non-blocking connect has finished (within the time-out); a failed one throws. */
BIND(uint8_t, jcl_Net_pollConnect, "sun.nio.ch.Net_pollConnect", (void *fdObj, int64_t timeout)) {
    const jcl_sock s = jcl_sock_of(fdObj);
    jcl_pollfd p;
    p.fd = s;
    p.events = POLLOUT;
    p.revents = 0;
    const int released = JCL_NET_BLOCK_BEGIN();
    const int r = JCL_POLL(&p, 1, (int)(timeout > 2147483647 ? 2147483647 : timeout));
    JCL_NET_BLOCK_END(released);
    if (r <= 0) return 0;
    int error = 0;
    jcl_socklen length = sizeof error;
    getsockopt(s, SOL_SOCKET, SO_ERROR, (char *)&error, &length);
    if (error != 0) {
        jcl_net_fail("java/net/ConnectException", "java/net/SocketException", "connect", error);
        return 0;
    }
    return 1;
}
BIND(int32_t, jcl_Net_available, "sun.nio.ch.Net_available", (void *fdObj)) {
#ifdef _WIN32
    u_long n = 0;
    if (ioctlsocket(jcl_sock_of(fdObj), FIONREAD, &n) != 0) {
        jcl_net_socket_fail("available", JCL_SOCK_ERRNO());
        return 0;
    }
    return (int32_t)n;
#else
    int n = 0;
    if (ioctl(jcl_sock_of(fdObj), FIONREAD, &n) != 0) {
        jcl_net_socket_fail("available", JCL_SOCK_ERRNO());
        return 0;
    }
    return n;
#endif
}
BIND(uint8_t, jcl_Net_discardOOB, "sun.nio.ch.Net_discardOOB", (void *fdObj)) { (void)fdObj; return 0; }

/* ---- sun.nio.ch.SocketDispatcher ---- */
static int32_t jcl_sock_read_status(int64_t n, int err, int32_t length) {
    if (n > 0 || length == 0) return (int32_t)n;
    if (n == 0) return JCL_IOS_EOF;
    if (err == JCL_E_WOULDBLOCK) return JCL_IOS_UNAVAILABLE;
    if (err == JCL_E_INTR) return JCL_IOS_INTERRUPTED;
    if (err == JCL_E_CONNRESET || err == JCL_E_CONNABORTED) {
        jcl_net_fail("java/net/SocketException", "java/io/IOException", "read", err);
        return 0;
    }
    jcl_net_socket_fail("read", err);
    return 0;
}
BIND(int32_t, jcl_SocketDispatcher_read0, "sun.nio.ch.SocketDispatcher_read0", (void *fdObj, int64_t address, int32_t length)) {
    const jcl_sock s = jcl_sock_of(fdObj);
    const int released = JCL_NET_BLOCK_BEGIN();
    const int n = (int)recv(s, (char *)(intptr_t)address, length, 0);
    const int err = n < 0 ? JCL_SOCK_ERRNO() : 0;
    JCL_NET_BLOCK_END(released);
    return jcl_sock_read_status(n, err, length);
}
BIND(int32_t, jcl_SocketDispatcher_write0, "sun.nio.ch.SocketDispatcher_write0", (void *fdObj, int64_t address, int32_t length)) {
    const jcl_sock s = jcl_sock_of(fdObj);
    const int released = JCL_NET_BLOCK_BEGIN();
    const int n = (int)send(s, (const char *)(intptr_t)address, length, 0);
    const int err = n < 0 ? JCL_SOCK_ERRNO() : 0;
    JCL_NET_BLOCK_END(released);
    if (n >= 0) return n;
    if (err == JCL_E_WOULDBLOCK) return JCL_IOS_UNAVAILABLE;
    if (err == JCL_E_INTR) return JCL_IOS_INTERRUPTED;
    jcl_net_fail("java/net/SocketException", "java/io/IOException", "write", err);
    return 0;
}
/* The gathering and scattering variants: an array of { void *base; size_t length } at the address. */
typedef struct { void *base; size_t length; } jcl_iovec;
BIND(int64_t, jcl_SocketDispatcher_readv0, "sun.nio.ch.SocketDispatcher_readv0", (void *fdObj, int64_t address, int32_t count)) {
    const jcl_iovec *iov = (const jcl_iovec *)(intptr_t)address;
    const jcl_sock s = jcl_sock_of(fdObj);
    int64_t total = 0;
    for (int32_t i = 0; i < count; i++) {
        if (iov[i].length == 0) continue;
        const int released = JCL_NET_BLOCK_BEGIN();
        const int n = (int)recv(s, (char *)iov[i].base, (int)iov[i].length, 0);
        const int err = n < 0 ? JCL_SOCK_ERRNO() : 0;
        JCL_NET_BLOCK_END(released);
        if (n <= 0) {
            if (total > 0) return total;
            return jcl_sock_read_status(n, err, 1);
        }
        total += n;
        if ((size_t)n < iov[i].length) break;
    }
    return total;
}
BIND(int64_t, jcl_SocketDispatcher_writev0, "sun.nio.ch.SocketDispatcher_writev0", (void *fdObj, int64_t address, int32_t count)) {
    const jcl_iovec *iov = (const jcl_iovec *)(intptr_t)address;
    const jcl_sock s = jcl_sock_of(fdObj);
    int64_t total = 0;
    for (int32_t i = 0; i < count; i++) {
        if (iov[i].length == 0) continue;
        const int released = JCL_NET_BLOCK_BEGIN();
        const int n = (int)send(s, (const char *)iov[i].base, (int)iov[i].length, 0);
        const int err = n < 0 ? JCL_SOCK_ERRNO() : 0;
        JCL_NET_BLOCK_END(released);
        if (n < 0) {
            if (total > 0) return total;
            if (err == JCL_E_WOULDBLOCK) return JCL_IOS_UNAVAILABLE;
            if (err == JCL_E_INTR) return JCL_IOS_INTERRUPTED;
            jcl_net_fail("java/net/SocketException", "java/io/IOException", "write", err);
            return 0;
        }
        total += n;
        if ((size_t)n < iov[i].length) break;
    }
    return total;
}
BIND(void, jcl_SocketDispatcher_close0, "sun.nio.ch.SocketDispatcher_close0", (int32_t fd)) {
    if (fd >= 0) JCL_SOCK_CLOSE((jcl_sock)fd);
}

/* ---- the wake-up pipe of a selector: a connected pair of sockets (a loopback pair on Windows) ---- */
static int jcl_socket_pair(jcl_sock pair[2]) {
    jcl_net_startup();
#ifdef _WIN32
    const jcl_sock listener = socket(AF_INET, SOCK_STREAM, 0);
    if (listener == JCL_SOCK_FAIL) return -1;
    struct sockaddr_in sa;
    jcl_socklen length = sizeof sa;
    jcl_sockaddr_in(&sa, 0x7f000001u, 0);
    pair[0] = pair[1] = JCL_SOCK_FAIL;
    if (bind(listener, (struct sockaddr *)&sa, sizeof sa) != 0 || listen(listener, 1) != 0
            || getsockname(listener, (struct sockaddr *)&sa, &length) != 0) {
        closesocket(listener);
        return -1;
    }
    pair[0] = socket(AF_INET, SOCK_STREAM, 0);
    if (pair[0] == JCL_SOCK_FAIL || connect(pair[0], (struct sockaddr *)&sa, sizeof sa) != 0) {
        if (pair[0] != JCL_SOCK_FAIL) closesocket(pair[0]);
        closesocket(listener);
        return -1;
    }
    pair[1] = accept(listener, NULL, NULL);
    closesocket(listener);
    if (pair[1] == JCL_SOCK_FAIL) {
        closesocket(pair[0]);
        return -1;
    }
    return 0;
#else
    int sv[2];
    if (socketpair(AF_UNIX, SOCK_STREAM, 0, sv) != 0) return -1;
    pair[0] = sv[0];
    pair[1] = sv[1];
    return 0;
#endif
}
/* (read end << 32) | write end. */
BIND(int64_t, jcl_IOUtil_makePipe, "sun.nio.ch.IOUtil_makePipe", (uint8_t blocking)) {
    jcl_sock pair[2];
    if (jcl_socket_pair(pair) != 0) {
        jcl_net_socket_fail("Pipe creation", JCL_SOCK_ERRNO());
        return 0;
    }
    if (!blocking) {
        jcl_sock_set_blocking(pair[0], 0);
        jcl_sock_set_blocking(pair[1], 0);
    }
    return ((int64_t)(int32_t)pair[0] << 32) | (int64_t)(uint32_t)(int32_t)pair[1];
}
BIND(int32_t, jcl_IOUtil_write1, "sun.nio.ch.IOUtil_write1", (int32_t fd, int8_t byte)) {
    const char b = (char)byte;
    const int n = (int)send((jcl_sock)fd, &b, 1, 0);
    if (n < 0) {
        const int err = JCL_SOCK_ERRNO();
        if (err == JCL_E_WOULDBLOCK) return JCL_IOS_UNAVAILABLE;
        if (err == JCL_E_INTR) return JCL_IOS_INTERRUPTED;
        jcl_net_socket_fail("write", err);
    }
    return n;
}
/* Reads and drops what is there; whether anything was. */
BIND(uint8_t, jcl_IOUtil_drain, "sun.nio.ch.IOUtil_drain", (int32_t fd)) {
    char buffer[128];
    int any = 0;
    for (;;) {
        const int n = (int)recv((jcl_sock)fd, buffer, sizeof buffer, 0);
        if (n <= 0) break;
        any = 1;
        if (n < (int)sizeof buffer) break;
    }
    return (uint8_t)any;
}
BIND(int32_t, jcl_IOUtil_drain1, "sun.nio.ch.IOUtil_drain1", (int32_t fd)) {
    char b;
    const int n = (int)recv((jcl_sock)fd, &b, 1, 0);
    return n > 0 ? 1 : 0;
}

/* ---- WEPoll: epoll on top of poll. The event structure is { uint32 events; (pad); int32 descriptor at +8 } ---- */
typedef struct { jcl_sock fd; uint32_t events; } jcl_ep_entry;
typedef struct { pthread_mutex_t lock; jcl_ep_entry *entries; int count; int capacity; } jcl_epoll;
#define JCL_EP_EVENT_SIZE 16
#define JCL_EP_DATA_OFFSET 8

BIND(int32_t, jcl_WEPoll_eventSize, "sun.nio.ch.WEPoll_eventSize", (void)) { return JCL_EP_EVENT_SIZE; }
BIND(int32_t, jcl_WEPoll_eventsOffset, "sun.nio.ch.WEPoll_eventsOffset", (void)) { return 0; }
BIND(int32_t, jcl_WEPoll_dataOffset, "sun.nio.ch.WEPoll_dataOffset", (void)) { return JCL_EP_DATA_OFFSET; }
BIND(int64_t, jcl_WEPoll_create, "sun.nio.ch.WEPoll_create", (void)) {
    jcl_net_startup();
    jcl_epoll *ep = (jcl_epoll *)calloc(1, sizeof *ep);
    if (ep == NULL) {
        jcl_net_socket_fail("epoll_create", 0);
        return 0;
    }
    pthread_mutex_init(&ep->lock, NULL);
    return (int64_t)(intptr_t)ep;
}
static jcl_ep_entry *jcl_ep_find(jcl_epoll *ep, jcl_sock fd) {
    for (int i = 0; i < ep->count; i++) if (ep->entries[i].fd == fd) return &ep->entries[i];
    return NULL;
}
/* 0 on success, else an errno-like code (EEXIST 17, ENOENT 2, EINVAL 22) like epoll_ctl. */
BIND(int32_t, jcl_WEPoll_ctl, "sun.nio.ch.WEPoll_ctl", (int64_t handle, int32_t opcode, int64_t socket, int32_t events)) {
    jcl_epoll *ep = (jcl_epoll *)(intptr_t)handle;
    const jcl_sock fd = (jcl_sock)socket;
    int result = 0;
    pthread_mutex_lock(&ep->lock);
    jcl_ep_entry *e = jcl_ep_find(ep, fd);
    if (opcode == 1) {          /* EPOLL_CTL_ADD */
        if (e != NULL) {
            result = 17;
        } else {
            if (ep->count == ep->capacity) {
                const int capacity = ep->capacity == 0 ? 64 : ep->capacity * 2;
                jcl_ep_entry *grown = (jcl_ep_entry *)realloc(ep->entries, sizeof *grown * (size_t)capacity);
                if (grown == NULL) {
                    result = 12;
                } else {
                    ep->entries = grown;
                    ep->capacity = capacity;
                }
            }
            if (result == 0) {
                ep->entries[ep->count].fd = fd;
                ep->entries[ep->count].events = (uint32_t)events;
                ep->count++;
            }
        }
    } else if (opcode == 3) {   /* EPOLL_CTL_MOD */
        if (e == NULL) result = 2; else e->events = (uint32_t)events;
    } else if (opcode == 2) {   /* EPOLL_CTL_DEL */
        if (e == NULL) {
            result = 2;
        } else {
            *e = ep->entries[ep->count - 1];
            ep->count--;
        }
    } else {
        result = 22;
    }
    pthread_mutex_unlock(&ep->lock);
    return result;
}
/* Waits (timeout in ms, -1: for ever) for the registered sockets; fills the event structures at the address. */
BIND(int32_t, jcl_WEPoll_wait, "sun.nio.ch.WEPoll_wait", (int64_t handle, int64_t address, int32_t maxEvents, int32_t timeout)) {
    jcl_epoll *ep = (jcl_epoll *)(intptr_t)handle;
    pthread_mutex_lock(&ep->lock);
    const int count = ep->count;
    jcl_pollfd *fds = (jcl_pollfd *)malloc(sizeof *fds * (size_t)(count > 0 ? count : 1));
    uint32_t *wanted = (uint32_t *)malloc(sizeof *wanted * (size_t)(count > 0 ? count : 1));
    int n = 0;
    for (int i = 0; i < count && fds != NULL && wanted != NULL; i++) {
        if (ep->entries[i].events == 0) continue;
        fds[n].fd = ep->entries[i].fd;
        fds[n].events = jcl_ev_to_sys(ep->entries[i].events);
        fds[n].revents = 0;
        wanted[n] = ep->entries[i].events;
        n++;
    }
    pthread_mutex_unlock(&ep->lock);
    if (fds == NULL || wanted == NULL) {
        free(fds);
        free(wanted);
        jcl_net_socket_fail("epoll_wait", 0);
        return 0;
    }
    int ready;
    const int released = JCL_NET_BLOCK_BEGIN();
    if (n == 0) {
#ifdef _WIN32
        if (timeout != 0) Sleep(timeout < 0 ? INFINITE : (DWORD)timeout);
#else
        if (timeout != 0) poll(NULL, 0, timeout);
#endif
        ready = 0;
    } else {
        ready = JCL_POLL(fds, n, timeout);
    }
    const int err = ready < 0 ? JCL_SOCK_ERRNO() : 0;
    JCL_NET_BLOCK_END(released);
    int delivered = 0;
    if (ready > 0) {
        pthread_mutex_lock(&ep->lock);
        for (int i = 0; i < n && delivered < maxEvents; i++) {
            if (fds[i].revents == 0) continue;
            uint32_t events = jcl_ev_from_sys(fds[i].revents);
            events &= (wanted[i] | JCL_EV_ERR | JCL_EV_HUP | JCL_EV_NVAL);
            if (events == 0) continue;
            char *slot = (char *)(intptr_t)address + (size_t)delivered * JCL_EP_EVENT_SIZE;
            memset(slot, 0, JCL_EP_EVENT_SIZE);
            *(uint32_t *)slot = events;
            *(int32_t *)(slot + JCL_EP_DATA_OFFSET) = (int32_t)fds[i].fd;
            delivered++;
            if (wanted[i] & JCL_EV_ONESHOT) {
                jcl_ep_entry *e = jcl_ep_find(ep, fds[i].fd);
                if (e != NULL) e->events = 0;
            }
        }
        pthread_mutex_unlock(&ep->lock);
    }
    free(fds);
    free(wanted);
    if (ready < 0 && err != JCL_E_INTR) {
        jcl_net_socket_fail("epoll_wait", err);
        return 0;
    }
    return delivered;
}
BIND(void, jcl_WEPoll_close, "sun.nio.ch.WEPoll_close", (int64_t handle)) {
    jcl_epoll *ep = (jcl_epoll *)(intptr_t)handle;
    if (ep == NULL) return;
    free(ep->entries);
    pthread_mutex_destroy(&ep->lock);
    free(ep);
}

/* No Unix domain sockets: sun.nio.ch.PipeImpl then connects its two ends through TCP on the loopback. */
BIND(uint8_t, jcl_UnixDomainSockets_init, "sun.nio.ch.UnixDomainSockets_init", (void)) { return 0; }

#include "nabu_interpreter.h"
#include "nabu_runtime.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <setjmp.h>
#ifndef _WIN32
#include <dlfcn.h>
#include <math.h>
#endif

/* Exception state. */
static jmp_buf interp_jmp_buf;
static int interp_jmp_active = 0;
static void *interp_pending_exception = NULL;
/* Floats and doubles in a slot (the 32 or 64 bits), and the conversions of the JVM (a NaN gives 0, the range saturates). */
static inline void *F2S(float v) { int b; memcpy(&b, &v, sizeof b); return (void *)(intptr_t)b; }
static inline void *D2S(double v) { long b; memcpy(&b, &v, sizeof b); return (void *)(intptr_t)b; }
static inline float S2F(void *s) { int b = (int)(intptr_t)s; float v; memcpy(&v, &b, sizeof v); return v; }
static inline double S2D(void *s) { long b = (long)(intptr_t)s; double v; memcpy(&v, &b, sizeof v); return v; }
static inline int J_D2I(double v) { return v != v ? 0 : v >= 2147483647.0 ? 2147483647 : v <= -2147483648.0 ? (-2147483647 - 1) : (int)v; }
static inline long J_D2L(double v) { return v != v ? 0 : v >= 9223372036854775807.0 ? 9223372036854775807L : v <= -9223372036854775808.0 ? (-9223372036854775807L - 1) : (long)v; }

static void jit_host_throw_arith_i(void);
static void interp_throw_array(int is_null) {
    void *exc = calloc(1, sizeof(nabu_object) + 2 * sizeof(void *));
    if (is_null) nabu_init_object_ex(exc, "java/lang/NullPointerException", "java/lang/RuntimeException");
    else nabu_init_object_ex(exc, "java/lang/ArrayIndexOutOfBoundsException", "java/lang/RuntimeException");
    interp_pending_exception = exc;
}
/* null and bounds check of an array access: the exception is pending and the instruction is left. A null array stays lenient
 * in JDK classes (reads give 0, stores are dropped): they run without their <clinit> and rely on it. */
#define INTERP_ARRAY_CHECK(arr, idx) if (((arr) == NULL && strict_nulls(m)) || ((arr) != NULL && (unsigned)(idx) >= (unsigned)*(int *)(arr))) { interp_throw_array((arr) == NULL); if (interp_jmp_active) longjmp(interp_jmp_buf, 1); running = 0; break; }

/* ---- Known strings set (for safe string concat) ---- */
#define NABU_MAX_STRINGS 65536
static void *nabu_known_strings[NABU_MAX_STRINGS];
static int nabu_known_string_count = 0;

static void nabu_register_string(void *p) {
    if (p && nabu_known_string_count < NABU_MAX_STRINGS) {
        nabu_known_strings[nabu_known_string_count++] = p;
    }
}

static int nabu_is_known_string(void *p) {
    for (int i = 0; i < nabu_known_string_count; i++) {
        if (nabu_known_strings[i] == p) return 1;
    }
    return 0;
}

/* ---- Formal itable cache for interface dispatch ---- */
typedef struct {
    char *recv_type;
    char *iface;
    char *mname;
    char *mdesc;
    nabu_interp_method_t *method;   /* interpreted method (or NULL) */
    void *native_fn;                /* native fn (or NULL) */
    int is_native;
    int valid;
} nabu_itable_entry_t;

#define NABU_ITABLE_SIZE 4096
static nabu_itable_entry_t nabu_itable[NABU_ITABLE_SIZE];
static int nabu_itable_count = 0;

static nabu_itable_entry_t *itable_lookup(const char *recv_type, const char *iface,
                                           const char *mname, const char *mdesc) {
    for (int i = 0; i < nabu_itable_count; i++) {
        if (!nabu_itable[i].valid) continue;
        if (recv_type && nabu_itable[i].recv_type && strcmp(nabu_itable[i].recv_type, recv_type) == 0 &&
            iface && nabu_itable[i].iface && strcmp(nabu_itable[i].iface, iface) == 0 &&
            mname && nabu_itable[i].mname && strcmp(nabu_itable[i].mname, mname) == 0 &&
            mdesc && nabu_itable[i].mdesc && strcmp(nabu_itable[i].mdesc, mdesc) == 0) {
            return &nabu_itable[i];
        }
    }
    return NULL;
}

void itable_insert(const char *recv_type, const char *iface, const char *mname, const char *mdesc,
                   nabu_interp_method_t *method, void *native_fn, int is_native) {
    if (nabu_itable_count >= NABU_ITABLE_SIZE) return;
    nabu_itable_entry_t *e = &nabu_itable[nabu_itable_count++];
    e->recv_type = recv_type ? strdup(recv_type) : NULL;
    e->iface = iface ? strdup(iface) : NULL;
    e->mname = mname ? strdup(mname) : NULL;
    e->mdesc = mdesc ? strdup(mdesc) : NULL;
    e->method = method;
    e->native_fn = native_fn;
    e->is_native = is_native;
    e->valid = 1;
}

/* ---- Lambda closures ---- */
typedef struct {
    char *impl_class;
    char *impl_name;
    char *impl_desc;
    int captured_count;
    void **captured;
    char *sam_name;
} nabu_closure_t;

#define NABU_MAX_CLOSURES 65536
static nabu_closure_t **nabu_closures = NULL;
static int nabu_closure_count = 0;

static int nabu_is_closure(void *p) {
    if (p == NULL || nabu_closures == NULL) return 0;
    for (int i = 0; i < nabu_closure_count; i++) {
        if ((void *)nabu_closures[i] == p) return 1;
    }
    return 0;
}

static nabu_closure_t *nabu_as_closure(void *p) {
    if (!nabu_is_closure(p)) return NULL;
    return (nabu_closure_t *)p;
}

/* ---- Method registry ---- */

static nabu_interp_method_t *method_head = NULL;
static nabu_interp_method_t **method_by_id = NULL;
static int method_cap = 0;
static int method_count_val = 0;

#define NABU_INTERP_ID_BASE 0x8000

void nabu_interp_init(void) {
    method_head = NULL;
    method_count_val = 0;
    nabu_jit_init();
}

int nabu_interp_register_method(nabu_interp_method_t *m) {
    if (m == NULL) return -1;
    m->next = method_head;
    method_head = m;
    if (method_count_val >= method_cap) {
        int newcap = method_cap == 0 ? 16 : method_cap * 2;
        nabu_interp_method_t **arr = (nabu_interp_method_t **)realloc(
            method_by_id, sizeof(nabu_interp_method_t *) * newcap);
        if (arr == NULL) return -1;
        method_by_id = arr;
        method_cap = newcap;
    }
    m->id = NABU_INTERP_ID_BASE + method_count_val;
    method_by_id[method_count_val] = m;
    method_count_val++;
    return m->id;
}

nabu_interp_method_t *nabu_interp_method_by_id(int id) {
    if (id < NABU_INTERP_ID_BASE) return NULL;
    int idx = id - NABU_INTERP_ID_BASE;
    if (idx < 0 || idx >= method_count_val) return NULL;
    return method_by_id[idx];
}

int nabu_interp_method_count(void) { return method_count_val; }

nabu_interp_method_t *nabu_interp_find_method(const char *class_name,
                                              const char *name,
                                              const char *desc) {
    for (nabu_interp_method_t *m = method_head; m != NULL; m = m->next) {
        if (class_name != NULL && m->class_name != NULL &&
            strcmp(m->class_name, class_name) != 0) continue;
        if (name != NULL && m->name != NULL && strcmp(m->name, name) != 0) continue;
        if (desc != NULL && m->desc != NULL && strcmp(m->desc, desc) != 0) continue;
        return m;
    }
    return NULL;
}

int nabu_interp_methods_for_class(const char *class_name, nabu_interp_method_t **out, int max) {
    int cnt = 0;
    for (nabu_interp_method_t *m = method_head; m != NULL && cnt < max; m = m->next) {
        if (class_name != NULL && m->class_name != NULL && strcmp(m->class_name, class_name) == 0) {
            if (out) out[cnt] = m;
            cnt++;
        }
    }
    return cnt;
}

/* ---- Frame helpers ---- */

static nabu_interp_frame_t *frame_new(nabu_interp_method_t *m) {
    nabu_interp_frame_t *f = (nabu_interp_frame_t *)calloc(1, sizeof(nabu_interp_frame_t));
    if (f == NULL) return NULL;
    f->method = m;
    int locals = m->max_locals > 0 ? m->max_locals : 16;
    int stack = m->max_stack > 0 ? m->max_stack : 16;
    f->locals = (void **)calloc(locals, sizeof(void *));
    f->stack = (void **)malloc(sizeof(void *) * stack);
    f->locals_cap = locals;
    f->stack_cap = stack;
    f->stack_top = 0;
    f->pc = 0;
    f->caller = NULL;
    f->result = NULL;
    f->has_result = 0;
    return f;
}

static void frame_free(nabu_interp_frame_t *f) {
    if (f == NULL) return;
    free(f->locals);
    free(f->stack);
    free(f);
}

static void push(nabu_interp_frame_t *f, void *v) {
    if (f->stack_top >= f->stack_cap) {
        int nc = f->stack_cap * 2;
        void **ns = (void **)realloc(f->stack, sizeof(void *) * nc);
        if (ns == NULL) { fprintf(stderr, "interp: stack overflow\n"); abort(); }
        f->stack = ns;
        f->stack_cap = nc;
    }
    f->stack[f->stack_top++] = v;
}

static void *pop(nabu_interp_frame_t *f) {
    if (f->stack_top <= 0) { fprintf(stderr, "interp: stack underflow\n"); abort(); }
    return f->stack[--f->stack_top];
}

/* ---- Multi-array allocation helper ---- */
static int elem_size_for(char t) {
    switch (t) {
        case 'Z': case 'B': return 1;
        case 'C': case 'S': return 2;
        case 'I': case 'F': return 4;
        case 'J': case 'D': return 8;
        default: return (int)sizeof(void *);
    }
}

static void *alloc_multi(const char *desc, int *counts, int n) {
    if (desc == NULL || desc[0] != '[' || n <= 0) return NULL;
    int size = counts[0];
    if (size < 0) size = 0;
    const char *comp = desc + 1;
    void *arr;
    if (comp[0] == '[' || comp[0] == 'L') {
        /* reference array (of arrays or objects) */
        arr = calloc(1, 8 + (size_t)size * sizeof(void *));
        if (arr != NULL) {
            *(int *)arr = size;
            if (n > 1) {
                void **elems = (void **)((char *)arr + 8);
                for (int i = 0; i < size; i++) {
                    elems[i] = alloc_multi(comp, counts + 1, n - 1);
                }
            }
        }
    } else {
        int esize = elem_size_for(comp[0]);
        arr = calloc(1, 8 + (size_t)size * esize);
        if (arr != NULL) *(int *)arr = size;
    }
    return arr;
}

/* ---- Param count from descriptor ---- */

static int count_params(const char *desc) {
    if (desc == NULL) return 0;
    const char *p = strchr(desc, '(');
    if (p == NULL) return 0;
    p++;
    int n = 0;
    while (*p != ')' && *p != 0) {
        if (*p == 'L') {
            while (*p != ';' && *p != 0) p++;
            if (*p == ';') p++;
            n++;
        } else if (*p == '[') {
            while (*p == '[') p++;
            if (*p == 'L') { while (*p != ';' && *p != 0) p++; if (*p == ';') p++; }
            else p++;
            n++;
        } else {
            p++;
            n++;
        }
    }
    return n;
}

static int is_void_return(const char *desc) {
    if (desc == NULL) return 1;
    const char *p = strchr(desc, ')');
    if (p == NULL) return 1;
    p++;
    return *p == 'V';
}

/* ---- Invocation ---- */

#define NABU_JIT_THRESHOLD_DEFAULT 100
static int jit_threshold(void) {
    static int value = 0;
    if (value == 0) {
        const char *e = getenv("NABU_JIT_THRESHOLD");
        value = (e != NULL && atoi(e) > 0) ? atoi(e) : NABU_JIT_THRESHOLD_DEFAULT;
    }
    return value;
}

/* Deoptimize a method: clear compiled_fn and reset state. */
void nabu_interp_deoptimize(nabu_interp_method_t *m) {
    if (m == NULL) return;
    m->compiled_fn = NULL;
    m->jit_attempted = 0;
    m->call_count = 0;
    m->deoptimized = 1;
}

/* Deoptimize all methods (e.g., on class redefinition). */
/* JIT invoke helper: resolve and invoke a method from JIT-compiled code. */
int nabu_jit_invoke_helper(void *method_ptr, int cp_index, int a0, int a1, int a2, int a3, int nargs) {
    nabu_interp_method_t *m = (nabu_interp_method_t *)method_ptr;
    if (m == NULL || m->const_pool == NULL || cp_index <= 0 || cp_index >= m->const_pool->count) {
        return 0;
    }
    nabu_cp_entry *e = &m->const_pool->entries[cp_index];
    char *cname = NULL, *mname = NULL, *mdesc = NULL;
    if (e->tag == 9 || e->tag == 10 || e->tag == 11) {
        int cls_idx = e->ref_class;
        int nat_idx = e->ref_nat;
        cname = (cls_idx > 0) ? nabu_cp_class_name(m->const_pool, cls_idx) : NULL;
        if (nat_idx > 0) {
            mname = nabu_cp_utf8(m->const_pool, m->const_pool->entries[nat_idx].nat_name);
            mdesc = nabu_cp_utf8(m->const_pool, m->const_pool->entries[nat_idx].nat_desc);
        }
    }
    if (cname == NULL || mname == NULL || mdesc == NULL) {
        return 0;
    }
    /* Find or create target method. */
    nabu_interp_method_t *target = nabu_interp_find_method(cname, mname, mdesc);
    if (target == NULL) {
        void *nf = nabu_find_native(cname, mname, mdesc);
        if (nf != NULL) {
            nabu_interp_method_t *nm = (nabu_interp_method_t *)calloc(1, sizeof(nabu_interp_method_t));
            nm->class_name = strdup(cname);
            nm->name = strdup(mname);
            nm->desc = strdup(mdesc);
            nm->is_native = 1;
            nm->native_fn = nf;
            nm->max_locals = 8; nm->max_stack = 8;
            nabu_interp_register_method(nm);
            target = nm;
        } else {
            /* Try auto-loading. */
            if (nabu_classfile_load_class(cname) == 0) {
                void *nf2 = nabu_find_native(cname, mname, mdesc);
                if (nf2 != NULL) {
                    nabu_interp_method_t *nm = (nabu_interp_method_t *)calloc(1, sizeof(nabu_interp_method_t));
                    nm->class_name = strdup(cname);
                    nm->name = strdup(mname);
                    nm->desc = strdup(mdesc);
                    nm->is_native = 1;
                    nm->native_fn = nf2;
                    nm->max_locals = 8; nm->max_stack = 8;
                    nabu_interp_register_method(nm);
                    target = nm;
                } else {
                    target = nabu_interp_find_method(cname, mname, mdesc);
                }
            }
        }
    }
    if (target == NULL) {
        return 0;
    }
    /* Build args array. */
    int pcnt = count_params(target->desc);
    int is_static = 1; /* invokestatic */
    int total_args = pcnt + (is_static ? 0 : 1);
    int n = total_args > 0 ? total_args : 1;
    void **args = (void **)calloc(n, sizeof(void *));
    /* For invokestatic, args[0..pcnt-1] are the method args. */
    int vals[4] = { a0, a1, a2, a3 };
    for (int i = 0; i < pcnt && i < 4; i++) {
        args[i] = (void *)(intptr_t)vals[i];
    }
    void *result = nabu_interp_invoke(target, args, total_args);
    free(args);
    return (int)(intptr_t)result;
}

/* Throw an exception from a native: the interpreter raises it when the native returns. */
void nabu_interp_throw(const char *cls, const char *super_cls) {
    void *exc = calloc(1, sizeof(nabu_object) + 2 * sizeof(void *));
    if (exc == NULL) return;
    nabu_init_object_ex(exc, cls, super_cls);
    interp_pending_exception = exc;
}

void nabu_interp_deoptimize_all(void) {
    for (nabu_interp_method_t *m = method_head; m != NULL; m = m->next) {
        if (m->compiled_fn != NULL) {
            m->compiled_fn = NULL;
            m->jit_attempted = 0;
            m->call_count = 0;
            m->deoptimized = 1;
        }
    }
}

/* ---- Static fields and class initialization ---- */

typedef struct { char *cls; char *name; void *value; } nabu_static_t;
static nabu_static_t **statics = NULL;
static int statics_count = 0, statics_cap = 0;

static nabu_static_t *static_slot(const char *cls, const char *name, int create) {
    for (int i = 0; i < statics_count; i++) {
        if (strcmp(statics[i]->cls, cls) == 0 && strcmp(statics[i]->name, name) == 0) return statics[i];
    }
    if (!create) return NULL;
    if (statics_count >= statics_cap) {
        int nc = statics_cap == 0 ? 64 : statics_cap * 2;
        nabu_static_t **a = (nabu_static_t **)realloc(statics, sizeof(nabu_static_t *) * nc);
        if (a == NULL) return NULL;
        statics = a; statics_cap = nc;
    }
    nabu_static_t *sl = (nabu_static_t *)calloc(1, sizeof(nabu_static_t));   /* a slot never moves: compiled code holds its address */
    if (sl == NULL) return NULL;
    sl->cls = strdup(cls);
    sl->name = strdup(name);
    statics[statics_count++] = sl;
    return sl;
}

/* The JDK classes come with natives and are not initialized here; classes of the program are (JLS 12.4, simplified:
 * on the first call of one of their methods or access of a static field, without superclass or cycle handling). */
static int is_jdk_class(const char *cls) {
    return strncmp(cls, "java/", 5) == 0 || strncmp(cls, "javax/", 6) == 0 || strncmp(cls, "jdk/", 4) == 0
        || strncmp(cls, "sun/", 4) == 0 || strncmp(cls, "NabuReflect", 11) == 0;
}

/* Code of the program throws a NullPointerException for a null array, object or receiver; the JDK classes run without their
 * <clinit> and rely on null reading as 0 and stores being dropped. */
static inline int strict_nulls(nabu_interp_method_t *m) {
    if (m->null_mode == 0) m->null_mode = (m->class_name != NULL && !is_jdk_class(m->class_name)) ? 2 : 1;
    return m->null_mode == 2;
}

static char **inited_classes = NULL;
static int inited_count = 0, inited_cap = 0;

static void ensure_clinit(const char *cls) {
    if (cls == NULL || is_jdk_class(cls)) return;
    for (int i = 0; i < inited_count; i++) if (strcmp(inited_classes[i], cls) == 0) return;
    if (inited_count >= inited_cap) {
        int nc = inited_cap == 0 ? 32 : inited_cap * 2;
        char **a = (char **)realloc(inited_classes, sizeof(char *) * nc);
        if (a == NULL) return;
        inited_classes = a; inited_cap = nc;
    }
    inited_classes[inited_count++] = strdup(cls);   /* marked first: a clinit that reads its own statics must not loop */
    nabu_interp_method_t *ci = nabu_interp_find_method(cls, "<clinit>", "()V");
    if (ci != NULL) nabu_interp_invoke(ci, NULL, 0);
}

/* ---- The method JIT (runtime/jit, libnabu_jit): numeric static methods become machine code ----
 *
 * A method that has been called NABU_JIT_THRESHOLD times and only computes with int, long, float and double (loops,
 * branches, calls of such methods) is handed to libnabu_jit, a Cranelift compiler that is loaded with dlopen when it is
 * there (NABU_JIT_LIB, or the library path). What it does not understand stays interpreted.
 * A compiled method has the type int64_t f(const int64_t *args): one argument per value, an int sign extended, a float as
 * its 32 bits and a double as its 64 bits; the result goes back the same way. */

#ifndef _WIN32
typedef struct {
    int (*cp_int)(void *cp, int idx, int32_t *out);
    int (*cp_long)(void *cp, int idx, int64_t *out);
    int (*cp_float)(void *cp, int idx, float *out);
    int (*cp_double)(void *cp, int idx, double *out);
    int (*resolve_static)(void *cp, int idx, void **method, char *desc, int cap);
    int (*call)(void *method, const int64_t *args, int nargs, int64_t *result);
    void (*throw_arith)(void);
    void **pending;
    int (*resolve_field)(void *cp, int idx, int is_static, int64_t *out, char *kind);
    void (*throw_exc)(int kind);
    void *(*new_array)(int atype, int count);
    int (*resolve_invoke)(void *cp, int idx, int op, void **method, char *desc, int cap);
    int (*call_virtual)(void *cp, int idx, const int64_t *args, int nargs, int64_t *result);
} nabu_jit_host_t;

static int jit_cp_int(void *cpv, int idx, int32_t *out) {
    nabu_cp *cp = (nabu_cp *)cpv;
    if (cp == NULL || idx <= 0 || idx >= cp->count || cp->entries[idx].tag != 3) return 0;
    *out = cp->entries[idx].int_val;
    return 1;
}
static int jit_cp_float(void *cpv, int idx, float *out) {
    nabu_cp *cp = (nabu_cp *)cpv;
    if (cp == NULL || idx <= 0 || idx >= cp->count || cp->entries[idx].tag != 4) return 0;
    int bits = cp->entries[idx].int_val;
    memcpy(out, &bits, sizeof bits);
    return 1;
}
static int jit_cp_long(void *cpv, int idx, int64_t *out) {
    nabu_cp *cp = (nabu_cp *)cpv;
    if (cp == NULL || idx <= 0 || idx >= cp->count || cp->entries[idx].tag != 5) return 0;
    *out = (int64_t)cp->entries[idx].long_val;
    return 1;
}
static int jit_cp_double(void *cpv, int idx, double *out) {
    nabu_cp *cp = (nabu_cp *)cpv;
    if (cp == NULL || idx <= 0 || idx >= cp->count || cp->entries[idx].tag != 6) return 0;
    int64_t bits = (int64_t)cp->entries[idx].long_val;
    memcpy(out, &bits, sizeof bits);
    return 1;
}

/* The method that an invokestatic names: the interpreter's method and its descriptor. */
static int jit_resolve_static(void *cpv, int idx, void **method, char *desc, int cap) {
    nabu_cp *cp = (nabu_cp *)cpv;
    if (cp == NULL || idx <= 0 || idx >= cp->count) return 0;
    nabu_cp_entry *e = &cp->entries[idx];
    if ((e->tag != 10 && e->tag != 11) || e->ref_nat <= 0 || e->ref_class <= 0) return 0;
    const char *cname = nabu_cp_class_name(cp, e->ref_class);
    const char *mname = nabu_cp_utf8(cp, cp->entries[e->ref_nat].nat_name);
    const char *mdesc = nabu_cp_utf8(cp, cp->entries[e->ref_nat].nat_desc);
    if (cname == NULL || mname == NULL || mdesc == NULL) return 0;
    nabu_interp_method_t *t = nabu_interp_find_method(cname, mname, mdesc);
    if (t == NULL) {
        nabu_classfile_load_class(cname);
        t = nabu_interp_find_method(cname, mname, mdesc);
    }
    if (t == NULL || (int)strlen(mdesc) >= cap) return 0;
    *method = t;
    strcpy(desc, mdesc);
    return 1;
}

/* One value of a descriptor type, between the slot of the interpreter and the 64 bits of the compiled code. */
static int64_t jit_pack(char type, void *slot) {
    switch (type) {
        case 'F': return (int64_t)(uint32_t)(int32_t)(intptr_t)slot;
        case 'J': case 'D': case 'L': case '[': return (int64_t)(intptr_t)slot;
        default: return (int64_t)(int32_t)(intptr_t)slot;
    }
}
static void *jit_unpack(char type, int64_t raw) {
    switch (type) {
        case 'V': return NULL;
        case 'J': case 'D': case 'L': case '[': return (void *)(intptr_t)raw;
        default: return (void *)(intptr_t)(int32_t)(uint32_t)raw;   /* an int, or the 32 bits of a float, sign extended */
    }
}
/* The next type of a descriptor of primitive types: its letter, and p moved behind it; 0 at the end of the parameters. */
static char jit_next_type(const char **p) {
    char c = **p;
    if (c == 0 || c == ')') return 0;
    if (c == 'L') {
        while (**p != 0 && **p != ';') (*p)++;
        if (**p == ';') (*p)++;
        return 'L';
    }
    if (c == '[') {
        while (**p == '[') (*p)++;
        if (**p == 'L') { while (**p != 0 && **p != ';') (*p)++; if (**p == ';') (*p)++; }
        else if (**p != 0) (*p)++;
        return '[';
    }
    (*p)++;
    return c;
}

/* Calls from compiled code: args one per value, the result in *result; non-zero when an exception is pending. */
static int jit_host_call(void *method, const int64_t *args, int nargs, int64_t *result) {
    nabu_interp_method_t *t = (nabu_interp_method_t *)method;
    void *slots[64];
    const char *p = strchr(t->desc, '(');
    p = p != NULL ? p + 1 : t->desc;
    int n = 0;
    if ((t->access & 0x0008) == 0 && nargs > 0) { slots[0] = jit_unpack('L', args[0]); n = 1; }   /* this */
    for (char c; n < nargs && n < 64 && (c = jit_next_type(&p)) != 0; n++) slots[n] = jit_unpack(c, args[n]);
    void *r = nabu_interp_invoke(t, slots, n);
    const char *rd = strchr(t->desc, ')');
    *result = rd != NULL ? jit_pack(rd[1], r) : 0;
    return interp_pending_exception != NULL;
}

static void jit_host_throw_arith(void) { jit_host_throw_arith_i(); }
#endif
static void jit_host_throw_arith_i(void) {
    void *exc = calloc(1, sizeof(nabu_object) + 2 * sizeof(void *));
    nabu_init_object_ex(exc, "java/lang/ArithmeticException", "java/lang/RuntimeException");
    interp_pending_exception = exc;
}
#ifndef _WIN32

/* A field named by the constant pool: its descriptor, and the byte offset in the object or the address of the static's slot. */
static int jit_resolve_field(void *cpv, int idx, int is_static, int64_t *out, char *kind) {
    nabu_cp *cp = (nabu_cp *)cpv;
    if (cp == NULL || idx <= 0 || idx >= cp->count) return 0;
    nabu_cp_entry *e = &cp->entries[idx];
    if (e->tag != 9 || e->ref_nat <= 0 || e->ref_class <= 0) return 0;
    const char *cname = nabu_cp_class_name(cp, e->ref_class);
    const char *fname = nabu_cp_utf8(cp, cp->entries[e->ref_nat].nat_name);
    const char *fdesc = nabu_cp_utf8(cp, cp->entries[e->ref_nat].nat_desc);
    if (cname == NULL || fname == NULL || fdesc == NULL || fdesc[0] == 0) return 0;
    *kind = fdesc[0];
    if (!is_static) {
        int fi = nabu_interp_field_index(cname, fname);
        if (fi < 0) return 0;
        *out = (int64_t)(sizeof(nabu_object) + (size_t)fi * sizeof(void *));
        return 1;
    }
    /* the special statics of the getstatic opcode (System.out, Boolean.TRUE, Integer.TYPE) and the JDK's stay interpreted */
    if (is_jdk_class(cname)) return 0;
    ensure_clinit(cname);
    nabu_static_t *sl = static_slot(cname, fname, 1);
    if (sl == NULL) return 0;
    *out = (int64_t)(intptr_t)&sl->value;
    return 1;
}

static void jit_host_throw_exc(int kind) {
    void *exc = calloc(1, sizeof(nabu_object) + 2 * sizeof(void *));
    if (kind == 1) nabu_init_object_ex(exc, "java/lang/NullPointerException", "java/lang/RuntimeException");
    else nabu_init_object_ex(exc, "java/lang/ArrayIndexOutOfBoundsException", "java/lang/RuntimeException");
    interp_pending_exception = exc;
}

static void *jit_new_array(int atype, int count) {
    int esize = 1;
    switch (atype) { case 5: case 9: esize = 2; break; case 6: case 10: esize = 4; break; case 7: case 11: esize = 8; break; default: esize = 1; break; }
    void *arr = calloc(1, 8 + (size_t)(count > 0 ? count : 0) * esize);
    if (arr != NULL) *(int *)arr = count;
    return arr;
}

/* A declaration of the method in the class or above it, abstract or not. */
static nabu_interp_method_t *jit_find_declaration(const char *cls, const char *name, const char *desc, int depth) {
    if (cls == NULL || depth > 40) return NULL;
    nabu_interp_method_t *t = nabu_interp_find_method(cls, name, desc);
    if (t != NULL) return t;
    t = jit_find_declaration(nabu_class_super_name(cls), name, desc, depth + 1);
    if (t != NULL) return t;
    int ic = nabu_get_interface_count(cls);
    char **ifs = ic > 0 ? nabu_get_interfaces(cls) : NULL;
    for (int i = 0; ifs != NULL && i < ic; i++) {
        t = jit_find_declaration(ifs[i], name, desc, depth + 1);
        if (t != NULL) return t;
    }
    return NULL;
}

/* invokespecial and invokevirtual: the method that the instruction names, found in the class or above it. Only classes of the
 * program (a JDK receiver can be anything); constructors stay interpreted. For invokevirtual the method found is the
 * one of the static type, the call itself looks at the receiver (jit_call_virtual). */
static int jit_resolve_invoke(void *cpv, int idx, int op, void **method, char *desc, int cap) {
    if (op == 0xb8) return jit_resolve_static(cpv, idx, method, desc, cap);
    nabu_cp *cp = (nabu_cp *)cpv;
    if (cp == NULL || idx <= 0 || idx >= cp->count) return 0;
    nabu_cp_entry *e = &cp->entries[idx];
    if ((e->tag != 10 && e->tag != 11) || e->ref_nat <= 0 || e->ref_class <= 0) return 0;
    const char *cname = nabu_cp_class_name(cp, e->ref_class);
    const char *mname = nabu_cp_utf8(cp, cp->entries[e->ref_nat].nat_name);
    const char *mdesc = nabu_cp_utf8(cp, cp->entries[e->ref_nat].nat_desc);
    if (cname == NULL || mname == NULL || mdesc == NULL || mname[0] == '<' || is_jdk_class(cname)) return 0;
    if (cname[0] == '[' || (int)strlen(mdesc) >= cap) return 0;
    nabu_classfile_load_class(cname);
    nabu_interp_method_t *t = nabu_interp_resolve_in_hierarchy(cname, mname, mdesc, 0);
    if (t == NULL && op == 0xb6) t = jit_find_declaration(cname, mname, mdesc, 0);   /* abstract: the receiver decides */
    if (t == NULL || t->is_native || (t->access & 0x0008) != 0) return 0;
    *method = t;
    strcpy(desc, mdesc);
    return 1;
}

/* A call-site cache for invokevirtual: (receiver type, call site) to the method of the receiver's class. */
typedef struct { const void *type; const void *cp; int idx; nabu_interp_method_t *target; } jit_vcache_t;
static jit_vcache_t jit_vcache[1024];

static int jit_call_virtual(void *cpv, int idx, const int64_t *args, int nargs, int64_t *result) {
    nabu_cp *cp = (nabu_cp *)cpv;
    void *recv = nargs > 0 ? (void *)(intptr_t)args[0] : NULL;
    if (recv == NULL) { jit_host_throw_exc(1); return 1; }
    const void *type = ((const void *const *)recv)[0];
    unsigned h = (unsigned)(((uintptr_t)type >> 4) ^ ((uintptr_t)cp >> 3) ^ (unsigned)idx * 2654435761u) & 1023u;
    jit_vcache_t *c = &jit_vcache[h];
    nabu_interp_method_t *t = (c->type == type && c->cp == cp && c->idx == idx) ? c->target : NULL;
    if (t == NULL) {
        nabu_cp_entry *e = &cp->entries[idx];
        const char *mname = nabu_cp_utf8(cp, cp->entries[e->ref_nat].nat_name);
        const char *mdesc = nabu_cp_utf8(cp, cp->entries[e->ref_nat].nat_desc);
        const char *rt = nabu_compiled_type_name(recv);
        if (rt != NULL) t = nabu_interp_resolve_in_hierarchy(rt, mname, mdesc, 0);
        if (t == NULL) {
            fprintf(stderr, "jit: no method %s%s for a receiver of type %s\n", mname, mdesc, rt != NULL ? rt : "?");
            jit_host_throw_exc(1);
            return 1;
        }
        c->type = type; c->cp = cp; c->idx = idx; c->target = t;
    }
    return jit_host_call(t, args, nargs, result);
}

static int jit_state = 0;   /* 0 not tried, 1 loaded, -1 not available */
static void *(*jit_compile_fn)(const uint8_t *, size_t, const char *, int, int, void *, void *) = NULL;

static int jit_load(void) {
    if (jit_state != 0) return jit_state > 0;
    jit_state = -1;
    const char *off = getenv("NABU_JIT");
    if (off != NULL && off[0] == '0') return 0;
    void *handle = NULL;
    const char *lib = getenv("NABU_JIT_LIB");
    if (lib != NULL && *lib) handle = dlopen(lib, RTLD_NOW | RTLD_LOCAL);
    if (handle == NULL) handle = dlopen("libnabu_jit.so", RTLD_NOW | RTLD_LOCAL);
    if (handle == NULL) return 0;
    int (*init)(const nabu_jit_host_t *) = (int (*)(const nabu_jit_host_t *))dlsym(handle, "nabu_jit_init");
    jit_compile_fn = (void *(*)(const uint8_t *, size_t, const char *, int, int, void *, void *))dlsym(handle, "nabu_jit_compile");
    if (init == NULL || jit_compile_fn == NULL) return 0;
    static nabu_jit_host_t host = { jit_cp_int, jit_cp_long, jit_cp_float, jit_cp_double, jit_resolve_static,
                                    jit_host_call, jit_host_throw_arith, &interp_pending_exception,
                                    jit_resolve_field, jit_host_throw_exc, jit_new_array,
                                    jit_resolve_invoke, jit_call_virtual };
    if (init(&host) != 0) return 0;
    jit_state = 1;
    return 1;
}
#endif

/* Compiles a method to machine code, or NULL when there is no JIT or it does not understand the method. */
void *nabu_jit_try_compile(nabu_interp_method_t *m) {
    if (m == NULL || m->code == NULL || m->code_len == 0 || m->is_native || m->exc_count > 0) return NULL;
#ifndef _WIN32
    if (!jit_load() || m->desc == NULL || m->class_name == NULL || is_jdk_class(m->class_name)) return NULL;   /* the JDK relies on the interpreter's lenient null arrays */
    void *code = jit_compile_fn(m->code, (size_t)m->code_len, m->desc, (m->access & 0x0008) != 0, m->max_locals, m->const_pool, m);
    if (getenv("NABU_JIT_TRACE") != NULL) fprintf(stderr, "jit: %s.%s%s %s\n", m->class_name, m->name, m->desc, code != NULL ? "compiled" : "not compiled");
    return code;
#else
    return NULL;
#endif
}

#ifndef _WIN32
/* A call of a compiled method from the interpreter. */
static void *jit_invoke(nabu_interp_method_t *m, void **args, int nargs) {
    int64_t buf[64];
    const char *p = strchr(m->desc, '(');
    p = p != NULL ? p + 1 : m->desc;
    int n = 0;
    if ((m->access & 0x0008) == 0 && nargs > 0) { buf[n] = jit_pack('L', args != NULL ? args[0] : NULL); n++; }   /* this */
    for (char c; n < nargs && n < 64 && (c = jit_next_type(&p)) != 0; n++) buf[n] = jit_pack(c, args != NULL ? args[n] : NULL);
    int64_t raw = ((int64_t (*)(const int64_t *))m->compiled_fn)(buf);
    const char *rd = strchr(m->desc, ')');
    return jit_unpack(rd != NULL ? rd[1] : 'V', raw);
}
#endif

/* The Class object of a primitive type, for getstatic Integer.TYPE and its siblings (int.class). */
static const char *primitive_of_wrapper(const char *cls) {
    if (strcmp(cls, "java/lang/Integer") == 0) return "int";
    if (strcmp(cls, "java/lang/Long") == 0) return "long";
    if (strcmp(cls, "java/lang/Boolean") == 0) return "boolean";
    if (strcmp(cls, "java/lang/Byte") == 0) return "byte";
    if (strcmp(cls, "java/lang/Short") == 0) return "short";
    if (strcmp(cls, "java/lang/Character") == 0) return "char";
    if (strcmp(cls, "java/lang/Float") == 0) return "float";
    if (strcmp(cls, "java/lang/Double") == 0) return "double";
    if (strcmp(cls, "java/lang/Void") == 0) return "void";
    return NULL;
}

/* Call the implementation method of a lambda or method reference with the arguments of its functional method. */
static void *call_closure(nabu_closure_t *cl, void **sam_args, int sam_params) {
    int total = cl->captured_count + sam_params;
    void **args = (void **)malloc(sizeof(void *) * (total > 0 ? total : 1));
    /* Detect instance method ref. */
    int is_instance = (count_params(cl->impl_desc) + 1 == (cl->captured_count + sam_params)) && (cl->captured_count + sam_params > 0);
    if (is_instance) {
        void *receiver;
        int pi = 0;
        void **params = (void **)malloc(sizeof(void *) * (cl->captured_count + sam_params));
        if (cl->captured_count > 0) {
            receiver = cl->captured[0];
            for (int i = 1; i < cl->captured_count; i++) params[pi++] = cl->captured[i];
            for (int i = 0; i < sam_params; i++) params[pi++] = sam_args[i];
        } else {
            receiver = (sam_params > 0) ? sam_args[0] : NULL;
            for (int i = 1; i < sam_params; i++) params[pi++] = sam_args[i];
        }
        args[0] = receiver;
        for (int i = 0; i < pi; i++) args[i + 1] = params[i];
        free(params);
    } else {
        for (int i = 0; i < cl->captured_count; i++) args[i] = cl->captured[i];
        for (int i = 0; i < sam_params; i++) args[cl->captured_count + i] = sam_args[i];
    }
    nabu_interp_method_t *target = nabu_interp_find_method(cl->impl_class, cl->impl_name, cl->impl_desc);
    if (target == NULL) {
        void *nf = nabu_find_native(cl->impl_class, cl->impl_name, cl->impl_desc);
        if (nf != NULL) {
            nabu_interp_method_t *nm = (nabu_interp_method_t *)calloc(1, sizeof(nabu_interp_method_t));
            nm->class_name = strdup(cl->impl_class);
            nm->name = strdup(cl->impl_name);
            nm->desc = strdup(cl->impl_desc);
            nm->is_native = 1;
            nm->native_fn = nf;
            nm->max_locals = 8; nm->max_stack = 8;
            target = nm;
        }
    }
    void *r = NULL;
    if (target != NULL) {
        r = nabu_interp_invoke(target, args, total);
    } else {
        fprintf(stderr, "interp: closure impl not found %s.%s%s\n", cl->impl_class, cl->impl_name, cl->impl_desc);
    }
    free(args);
    return r;
}

/* The arguments of a call into the local variables of the frame. A long or double parameter takes two local variable slots
 * in the numbering of the bytecode (lload_0, then iload_2 for the next parameter); the arguments themselves come one per
 * value, the receiver first. */
static void place_args(nabu_interp_frame_t *f, nabu_interp_method_t *m, void **args, int nargs) {
    if (args == NULL) return;
    const char *p = m->desc != NULL ? strchr(m->desc, '(') : NULL;
    if (p == NULL) {
        for (int i = 0; i < nargs && i < f->locals_cap; i++) f->locals[i] = args[i];
        return;
    }
    p++;
    int slot = 0, ai = 0;
    if ((m->access & 0x0008) == 0 && nargs > 0) {
        if (slot < f->locals_cap) f->locals[slot] = args[0];
        slot++;
        ai++;
    }
    while (*p != 0 && *p != ')' && ai < nargs) {
        const int wide = *p == 'J' || *p == 'D';
        if (*p == 'L') {
            while (*p != 0 && *p != ';') p++;
            if (*p == ';') p++;
        } else if (*p == '[') {
            while (*p == '[') p++;
            if (*p == 'L') { while (*p != 0 && *p != ';') p++; if (*p == ';') p++; }
            else if (*p != 0) p++;
        } else {
            p++;
        }
        if (slot < f->locals_cap) f->locals[slot] = args[ai];
        slot += wide ? 2 : 1;
        ai++;
    }
}

/* A method with a branch backwards (a loop) is compiled at its first call: it may be called once and run for a long time. */
static int has_loop(nabu_interp_method_t *m) {
    if (m->code == NULL) return 0;
    for (int i = 0; i + 2 < (int)m->code_len; i++) {
        unsigned char op = m->code[i];
        if (((op >= 0x99 && op <= 0xa7) || op == 0xc6 || op == 0xc7) && m->code[i + 1] >= 0x80) return 1;
    }
    return 0;
}

/* A method of a class, its superclasses or (default methods) its interfaces: the first one that has code or is native. */
/* The class of an exception that nobody caught (NULL when there is none); the exception stays pending. */
const char *nabu_interp_uncaught_exception(void) {
    return interp_pending_exception != NULL ? nabu_compiled_type_name(interp_pending_exception) : NULL;
}

nabu_interp_method_t *nabu_interp_resolve_in_hierarchy(const char *cls, const char *name, const char *desc, int depth) {
    if (cls == NULL || depth > 40) return NULL;
    nabu_interp_method_t *t = nabu_interp_find_method(cls, name, desc);
    if (t != NULL && (t->code != NULL || t->is_native)) return t;
    t = nabu_interp_resolve_in_hierarchy(nabu_class_super_name(cls), name, desc, depth + 1);
    if (t != NULL) return t;
    int ic = nabu_get_interface_count(cls);
    char **ifs = ic > 0 ? nabu_get_interfaces(cls) : NULL;
    for (int i = 0; ifs != NULL && i < ic; i++) {
        t = nabu_interp_resolve_in_hierarchy(ifs[i], name, desc, depth + 1);
        if (t != NULL) return t;
    }
    return NULL;
}

/* invokevirtual picks the method of the receiver's class (an override), invokespecial and an unfound method walk up from
 * the class that the instruction names (super.m(), an inherited method). Only for classes of the program: the JDK's
 * receivers can be anything, an identity boxed int as well. */
static nabu_interp_method_t *resolve_invoke_target(nabu_interp_frame_t *f, nabu_cp *cp, int idx, int op, nabu_interp_method_t *cur) {
    nabu_cp_entry *e = &cp->entries[idx];
    if ((e->tag != 10 && e->tag != 11) || e->ref_class <= 0 || e->ref_nat <= 0) return cur;
    const char *cname = nabu_cp_class_name(cp, e->ref_class);
    const char *mname = nabu_cp_utf8(cp, cp->entries[e->ref_nat].nat_name);
    const char *mdesc = nabu_cp_utf8(cp, cp->entries[e->ref_nat].nat_desc);
    if (cname == NULL || mname == NULL || mdesc == NULL || is_jdk_class(cname)) return cur;
    if (op == 0xb6) {
        int pc = count_params(mdesc);
        if (f->stack_top > pc) {
            void *recv = f->stack[f->stack_top - 1 - pc];
            const char *rt = recv != NULL ? nabu_compiled_type_name(recv) : NULL;
            if (rt != NULL && (cur == NULL || cur->class_name == NULL || strcmp(rt, cur->class_name) != 0)) {
                nabu_interp_method_t *t = nabu_interp_resolve_in_hierarchy(rt, mname, mdesc, 0);
                if (t != NULL) return t;
            }
        }
    }
    if (cur == NULL || (cur->code == NULL && !cur->is_native)) {
        nabu_interp_method_t *t = nabu_interp_resolve_in_hierarchy(cname, mname, mdesc, 0);
        if (t != NULL) return t;
    }
    return cur;
}

void *nabu_interp_invoke(nabu_interp_method_t *m, void **args, int nargs) {
    if (m == NULL) return NULL;
    if (m->class_name != NULL && m->name != NULL && strcmp(m->name, "<clinit>") != 0) ensure_clinit(m->class_name);
    /* If JIT-compiled and not deoptimized, call the compiled function directly. */
#ifndef _WIN32
    if (m->compiled_fn != NULL && !m->deoptimized) return jit_invoke(m, args, nargs);
#endif
    /* Hot method detection: increment call counter. */
    m->call_count++;
    if ((m->call_count >= jit_threshold() || (m->call_count == 1 && has_loop(m))) && !m->jit_attempted && m->code != NULL && m->code_len > 0) {
        m->jit_attempted = 1;
        /* Attempt JIT compilation for any bytecode. The Cranelift JIT will
         * return NULL if it cannot handle the bytecode. */
        void *compiled = nabu_jit_try_compile(m);
        if (compiled != NULL) {
            m->compiled_fn = compiled;
#ifndef _WIN32
            return jit_invoke(m, args, nargs);   /* this call too */
#endif
        }
    }
    if (m->is_native) {
        nabu_interp_frame_t *f = frame_new(m);
        if (m->native_fn != NULL) {
            typedef void (*native_fn_t)(nabu_interp_frame_t *, void **, int);
            ((native_fn_t)m->native_fn)(f, args, nargs);
        }
        void *r = f->has_result ? f->result : NULL;
        frame_free(f);
        return r;
    }
    nabu_interp_frame_t *f = frame_new(m);
    place_args(f, m, args, nargs);
    void *result = NULL;
    int running = 1;
    int prev_active = interp_jmp_active;
    jmp_buf prev_jmp;
    if (interp_jmp_active) {
        memcpy(prev_jmp, interp_jmp_buf, sizeof(jmp_buf));
    }
    interp_jmp_active = 1;
    while (running && f->pc < m->code_len) {
        if (setjmp(interp_jmp_buf) != 0) {
            void *exc = interp_pending_exception;
            interp_pending_exception = NULL;
            int found = 0;
            for (int h = 0; h < m->exc_count; h++) {
                if (f->pc >= m->exc_start_pc[h] && f->pc <= m->exc_end_pc[h]) {
                    int type_idx = m->exc_type_idx[h];
                    int match = 0;
                    if (type_idx == 0) {
                        match = 1;
                    } else {
                        char *tname = NULL;
                        if (m->const_pool != NULL && type_idx < m->const_pool->count) {
                            tname = nabu_cp_class_name(m->const_pool, type_idx);
                        }
                        if (tname == NULL) match = 1;
                        else if (exc != NULL) {
                            nabu_type_info *cur = ((nabu_object *)exc)->type;
                            while (cur != NULL) {
                                if (cur->name && strcmp(cur->name, tname) == 0) { match = 1; break; }
                                cur = cur->super_type;
                            }
                        }
                    }
                    if (match) {
                        f->pc = m->exc_handler_pc[h];
                        push(f, exc);
                        found = 1;
                        break;
                    }
                }
            }
            if (!found) {
                interp_pending_exception = exc;
                running = 0;
            }
            continue;
        }
        unsigned char op = m->code[f->pc++];


        switch (op) {
            case 0x00: break;
            case 0x01: push(f, NULL); break;
            case 0x02: push(f, (void *)(intptr_t)-1); break;
            case 0x03: push(f, (void *)(intptr_t)0); break;
            case 0x04: push(f, (void *)(intptr_t)1); break;
            case 0x05: push(f, (void *)(intptr_t)2); break;
            case 0x06: push(f, (void *)(intptr_t)3); break;
            case 0x07: push(f, (void *)(intptr_t)4); break;
            case 0x08: push(f, (void *)(intptr_t)5); break;
            case 0x09: push(f, (void *)(intptr_t)0L); break;
            case 0x0a: push(f, (void *)(intptr_t)1L); break;
            case 0x0b: { float v = 0.0f; int bits; memcpy(&bits, &v, sizeof(bits)); push(f, (void *)(intptr_t)bits); break; }
            case 0x0c: { float v = 1.0f; int bits; memcpy(&bits, &v, sizeof(bits)); push(f, (void *)(intptr_t)bits); break; }
            case 0x0d: { float v = 2.0f; int bits; memcpy(&bits, &v, sizeof(bits)); push(f, (void *)(intptr_t)bits); break; }
            case 0x0e: { double v = 0.0; long bits; memcpy(&bits, &v, sizeof(bits)); push(f, (void *)(intptr_t)bits); break; }
            case 0x0f: { double v = 1.0; long bits; memcpy(&bits, &v, sizeof(bits)); push(f, (void *)(intptr_t)bits); break; }
            case 0x10: { int8_t v = (int8_t)m->code[f->pc++]; push(f, (void *)(intptr_t)v); break; }
            case 0x11: { int16_t v = (int16_t)((m->code[f->pc] << 8) | m->code[f->pc+1]); f->pc += 2; push(f, (void *)(intptr_t)v); break; }
            case 0x12: {
                int idx = m->code[f->pc++];
                void *val = NULL;
                if (m->const_pool != NULL && idx > 0 && idx < m->const_pool->count) {
                    nabu_cp_entry *e = &m->const_pool->entries[idx];
                    if (e->tag == 3) val = (void *)(intptr_t)e->int_val;
                    else if (e->tag == 4) val = (void *)(intptr_t)e->int_val; /* float bits */
                    else if (e->tag == 5) val = (void *)(intptr_t)e->long_val; /* long */
                    else if (e->tag == 6) val = (void *)(intptr_t)e->long_val; /* double bits */
                    else if (e->tag == 7) { char *cn = nabu_cp_class_name(m->const_pool, idx); val = cn ? (void *)strdup(cn) : NULL; } /* handle (char*) */
                    else if (e->tag == 8) { char *s = nabu_cp_utf8(m->const_pool, e->str_index); val = s ? strdup(s) : NULL; }
                }
                push(f, val);
                break;
            }
            case 0x13: {
                int idx = (m->code[f->pc] << 8) | m->code[f->pc+1]; f->pc += 2;
                void *val = NULL;
                if (m->const_pool != NULL && idx > 0 && idx < m->const_pool->count) {
                    nabu_cp_entry *e = &m->const_pool->entries[idx];
                    if (e->tag == 3) val = (void *)(intptr_t)e->int_val;
                    else if (e->tag == 4) val = (void *)(intptr_t)e->int_val;
                    else if (e->tag == 5) val = (void *)(intptr_t)e->long_val;
                    else if (e->tag == 6) val = (void *)(intptr_t)e->long_val;
                    else if (e->tag == 7) { char *cn = nabu_cp_class_name(m->const_pool, idx); val = cn ? (void *)strdup(cn) : NULL; }
                    else if (e->tag == 8) { char *s = nabu_cp_utf8(m->const_pool, e->str_index); val = s ? strdup(s) : NULL; }
                }
                push(f, val);
                break;
            }
            case 0x14: { /* ldc2_w */
                int idx = (m->code[f->pc] << 8) | m->code[f->pc+1]; f->pc += 2;
                void *val = NULL;
                if (m->const_pool != NULL && idx > 0 && idx < m->const_pool->count) {
                    nabu_cp_entry *e = &m->const_pool->entries[idx];
                    if (e->tag == 5) val = (void *)(intptr_t)e->long_val;
                    else if (e->tag == 6) val = (void *)(intptr_t)e->long_val;
                }
                push(f, val);
                break;
            }
            case 0x15: { int idx = m->code[f->pc++]; push(f, f->locals[idx]); break; }
            case 0x19: { int idx = m->code[f->pc++]; push(f, f->locals[idx]); break; }
            case 0x1a: push(f, f->locals[0]); break;
            case 0x1b: push(f, f->locals[1]); break;
            case 0x1c: push(f, f->locals[2]); break;
            case 0x1d: push(f, f->locals[3]); break;
            case 0x2a: push(f, f->locals[0]); break;
            case 0x2b: push(f, f->locals[1]); break;
            case 0x2c: push(f, f->locals[2]); break;
            case 0x2d: push(f, f->locals[3]); break;
            /* long/float/double locals load */
            case 0x16: { int idx = m->code[f->pc++]; push(f, f->locals[idx]); break; }
            case 0x1e: push(f, f->locals[0]); break;
            case 0x1f: push(f, f->locals[1]); break;
            case 0x20: push(f, f->locals[2]); break;
            case 0x21: push(f, f->locals[3]); break;
            case 0x17: { int idx = m->code[f->pc++]; push(f, f->locals[idx]); break; }
            case 0x22: push(f, f->locals[0]); break;
            case 0x23: push(f, f->locals[1]); break;
            case 0x24: push(f, f->locals[2]); break;
            case 0x25: push(f, f->locals[3]); break;
            case 0x18: { int idx = m->code[f->pc++]; push(f, f->locals[idx]); break; }
            case 0x26: push(f, f->locals[0]); break;
            case 0x27: push(f, f->locals[1]); break;
            case 0x28: push(f, f->locals[2]); break;
            case 0x29: push(f, f->locals[3]); break;
            case 0x36: { int idx = m->code[f->pc++]; f->locals[idx] = pop(f); break; }
            case 0x3a: { int idx = m->code[f->pc++]; f->locals[idx] = pop(f); break; }
            case 0x3b: f->locals[0] = pop(f); break;
            case 0x3c: f->locals[1] = pop(f); break;
            case 0x3d: f->locals[2] = pop(f); break;
            case 0x3e: f->locals[3] = pop(f); break;
            case 0x2e: { /* iaload */
                int idx = (int)(intptr_t)pop(f);
                void *arr = pop(f);
                INTERP_ARRAY_CHECK(arr, idx)
                int v = 0;
                if (arr != NULL) v = *(int *)((char *)arr + 8 + idx * 4);
                push(f, (void *)(intptr_t)v);
                break;
            }
            case 0x32: { /* aaload */
                int idx = (int)(intptr_t)pop(f);
                void *arr = pop(f);
                INTERP_ARRAY_CHECK(arr, idx)
                void *v = NULL;
                if (arr != NULL) v = ((void **)((char *)arr + 8))[idx];
                push(f, v);
                break;
            }
            case 0x33: { /* baload */
                int idx = (int)(intptr_t)pop(f);
                void *arr = pop(f);
                INTERP_ARRAY_CHECK(arr, idx)
                int v = 0;
                if (arr != NULL) v = (signed char)*((char *)arr + 8 + idx);
                push(f, (void *)(intptr_t)v);
                break;
            }
            case 0x34: { /* caload */
                int idx = (int)(intptr_t)pop(f);
                void *arr = pop(f);
                INTERP_ARRAY_CHECK(arr, idx)
                int v = 0;
                if (arr != NULL) v = *(unsigned short *)((char *)arr + 8 + idx * 2);
                push(f, (void *)(intptr_t)v);
                break;
            }
            case 0x35: { /* saload */
                int idx = (int)(intptr_t)pop(f);
                void *arr = pop(f);
                INTERP_ARRAY_CHECK(arr, idx)
                int v = 0;
                if (arr != NULL) v = *(short *)((char *)arr + 8 + idx * 2);
                push(f, (void *)(intptr_t)v);
                break;
            }
            case 0x2f: { /* laload */
                int idx = (int)(intptr_t)pop(f);
                void *arr = pop(f);
                INTERP_ARRAY_CHECK(arr, idx)
                long v = 0;
                if (arr != NULL) v = *(long *)((char *)arr + 8 + (size_t)idx * 8);
                push(f, (void *)(intptr_t)v);
                break;
            }
            case 0x30: { /* faload */
                int idx = (int)(intptr_t)pop(f);
                void *arr = pop(f);
                INTERP_ARRAY_CHECK(arr, idx)
                float v = 0.0f;
                if (arr != NULL) v = *(float *)((char *)arr + 8 + (size_t)idx * 4);
                int bits; memcpy(&bits, &v, sizeof(bits));
                push(f, (void *)(intptr_t)bits);
                break;
            }
            case 0x31: { /* daload */
                int idx = (int)(intptr_t)pop(f);
                void *arr = pop(f);
                INTERP_ARRAY_CHECK(arr, idx)
                double v = 0.0;
                if (arr != NULL) v = *(double *)((char *)arr + 8 + (size_t)idx * 8);
                long bits; memcpy(&bits, &v, sizeof(bits));
                push(f, (void *)(intptr_t)bits);
                break;
            }
            case 0x4b: f->locals[0] = pop(f); break;
            case 0x4c: f->locals[1] = pop(f); break;
            case 0x4d: f->locals[2] = pop(f); break;
            case 0x4e: f->locals[3] = pop(f); break;
            /* long/float/double stores */
            case 0x37: { int idx = m->code[f->pc++]; f->locals[idx] = pop(f); break; }
            case 0x3f: f->locals[0] = pop(f); break;
            case 0x40: f->locals[1] = pop(f); break;
            case 0x41: f->locals[2] = pop(f); break;
            case 0x42: f->locals[3] = pop(f); break;
            case 0x38: { int idx = m->code[f->pc++]; f->locals[idx] = pop(f); break; }
            case 0x43: f->locals[0] = pop(f); break;
            case 0x44: f->locals[1] = pop(f); break;
            case 0x45: f->locals[2] = pop(f); break;
            case 0x46: f->locals[3] = pop(f); break;
            case 0x39: { int idx = m->code[f->pc++]; f->locals[idx] = pop(f); break; }
            case 0x47: f->locals[0] = pop(f); break;
            case 0x48: f->locals[1] = pop(f); break;
            case 0x49: f->locals[2] = pop(f); break;
            case 0x4a: f->locals[3] = pop(f); break;
            case 0x4f: { /* iastore */
                int val = (int)(intptr_t)pop(f);
                int idx = (int)(intptr_t)pop(f);
                void *arr = pop(f);
                INTERP_ARRAY_CHECK(arr, idx)
                if (arr != NULL) *(int *)((char *)arr + 8 + idx * 4) = val;
                break;
            }
            case 0x53: { /* aastore */
                void *val = pop(f);
                int idx = (int)(intptr_t)pop(f);
                void *arr = pop(f);
                INTERP_ARRAY_CHECK(arr, idx)
                if (arr != NULL) ((void **)((char *)arr + 8))[idx] = val;
                break;
            }
            case 0x54: { /* bastore */
                int val = (int)(intptr_t)pop(f);
                int idx = (int)(intptr_t)pop(f);
                void *arr = pop(f);
                INTERP_ARRAY_CHECK(arr, idx)
                if (arr != NULL) *((char *)arr + 8 + idx) = (char)val;
                break;
            }
            case 0x55: { /* castore */
                int val = (int)(intptr_t)pop(f);
                int idx = (int)(intptr_t)pop(f);
                void *arr = pop(f);
                INTERP_ARRAY_CHECK(arr, idx)
                if (arr != NULL) *(unsigned short *)((char *)arr + 8 + idx * 2) = (unsigned short)val;
                break;
            }
            case 0x56: { /* sastore */
                int val = (int)(intptr_t)pop(f);
                int idx = (int)(intptr_t)pop(f);
                void *arr = pop(f);
                INTERP_ARRAY_CHECK(arr, idx)
                if (arr != NULL) *(short *)((char *)arr + 8 + idx * 2) = (short)val;
                break;
            }
            case 0x50: { /* lastore */
                long val = (long)(intptr_t)pop(f);
                int idx = (int)(intptr_t)pop(f);
                void *arr = pop(f);
                INTERP_ARRAY_CHECK(arr, idx)
                if (arr != NULL) *(long *)((char *)arr + 8 + (size_t)idx * 8) = val;
                break;
            }
            case 0x51: { /* fastore */
                int bits = (int)(intptr_t)pop(f);
                float val; memcpy(&val, &bits, sizeof(val));
                int idx = (int)(intptr_t)pop(f);
                void *arr = pop(f);
                INTERP_ARRAY_CHECK(arr, idx)
                if (arr != NULL) *(float *)((char *)arr + 8 + (size_t)idx * 4) = val;
                break;
            }
            case 0x52: { /* dastore */
                long bits = (long)(intptr_t)pop(f);
                double val; memcpy(&val, &bits, sizeof(val));
                int idx = (int)(intptr_t)pop(f);
                void *arr = pop(f);
                INTERP_ARRAY_CHECK(arr, idx)
                if (arr != NULL) *(double *)((char *)arr + 8 + (size_t)idx * 8) = val;
                break;
            }
            case 0x57: pop(f); break;
            case 0x58: pop(f); break; /* our longs/ints are one slot */
            case 0x59: { void *v = f->stack[f->stack_top-1]; push(f, v); break; }
            case 0x5a: { /* dup_x1: ..., a, b → ..., b, a, b */
                void *v1 = pop(f); void *v2 = pop(f);
                push(f, v1); push(f, v2); push(f, v1);
                break;
            }
            case 0x5b: { /* dup_x2: ..., a, b, c → ..., c, a, b, c */
                void *v1 = pop(f); void *v2 = pop(f); void *v3 = pop(f);
                push(f, v1); push(f, v3); push(f, v2); push(f, v1);
                break;
            }
            case 0x5c: { /* dup2: ..., a, b → ..., a, b, a, b */
                void *v1 = pop(f); void *v2 = pop(f);
                push(f, v2); push(f, v1); push(f, v2); push(f, v1);
                break;
            }
            case 0x5f: { /* swap: ..., a, b → ..., b, a */
                void *v1 = pop(f); void *v2 = pop(f);
                push(f, v1); push(f, v2);
                break;
            }
            case 0x60: { int b=(int)(intptr_t)pop(f); int a=(int)(intptr_t)pop(f); push(f,(void*)(intptr_t)(a+b)); break; }
            case 0x64: { int b=(int)(intptr_t)pop(f); int a=(int)(intptr_t)pop(f); push(f,(void*)(intptr_t)(a-b)); break; }
            case 0x68: { int b=(int)(intptr_t)pop(f); int a=(int)(intptr_t)pop(f); push(f,(void*)(intptr_t)(a*b)); break; }
            case 0x6c: {
                int b = (int)(intptr_t)pop(f);
                int a = (int)(intptr_t)pop(f);
                if (b == 0) {
                    void *exc = calloc(1, sizeof(nabu_object) + 2*sizeof(void*));
                    nabu_init_object_ex(exc, "java/lang/ArithmeticException", "java/lang/RuntimeException");
                    interp_pending_exception = exc;
                    if (interp_jmp_active) longjmp(interp_jmp_buf, 1);
                    running = 0; break;
                }
                push(f, (void *)(intptr_t)(b == -1 ? (int)(0u - (unsigned)a) : a / b)); break;
            }
            case 0x70: { int b=(int)(intptr_t)pop(f); int a=(int)(intptr_t)pop(f);
                if (b == 0) { jit_host_throw_arith_i(); if (interp_jmp_active) longjmp(interp_jmp_buf, 1); running = 0; break; }
                push(f,(void*)(intptr_t)(b==-1?0:a%b)); break; }
            case 0x74: { int a=(int)(intptr_t)pop(f); push(f,(void*)(intptr_t)(-a)); break; }
            /* long/float/double arithmetic */
            case 0x61: { long b=(long)(intptr_t)pop(f); long a=(long)(intptr_t)pop(f); push(f,(void*)(intptr_t)(a+b)); break; }
            case 0x62: { int bb=(int)(intptr_t)pop(f); int aa=(int)(intptr_t)pop(f); float fb,fa; memcpy(&fb,&bb,sizeof(fb)); memcpy(&fa,&aa,sizeof(fa)); float r=fa+fb; int rb; memcpy(&rb,&r,sizeof(rb)); push(f,(void*)(intptr_t)rb); break; }
            case 0x63: { long bb=(long)(intptr_t)pop(f); long aa=(long)(intptr_t)pop(f); double db,da; memcpy(&db,&bb,sizeof(db)); memcpy(&da,&aa,sizeof(da)); double r=da+db; long rb; memcpy(&rb,&r,sizeof(rb)); push(f,(void*)(intptr_t)rb); break; }
            case 0x65: { long b=(long)(intptr_t)pop(f); long a=(long)(intptr_t)pop(f); push(f,(void*)(intptr_t)(a-b)); break; }
            case 0x66: { int bb=(int)(intptr_t)pop(f); int aa=(int)(intptr_t)pop(f); float fb,fa; memcpy(&fb,&bb,sizeof(fb)); memcpy(&fa,&aa,sizeof(fa)); float r=fa-fb; int rb; memcpy(&rb,&r,sizeof(rb)); push(f,(void*)(intptr_t)rb); break; }
            case 0x67: { long bb=(long)(intptr_t)pop(f); long aa=(long)(intptr_t)pop(f); double db,da; memcpy(&db,&bb,sizeof(db)); memcpy(&da,&aa,sizeof(da)); double r=da-db; long rb; memcpy(&rb,&r,sizeof(rb)); push(f,(void*)(intptr_t)rb); break; }
            case 0x69: { long b=(long)(intptr_t)pop(f); long a=(long)(intptr_t)pop(f); push(f,(void*)(intptr_t)(a*b)); break; }
            case 0x6a: { int bb=(int)(intptr_t)pop(f); int aa=(int)(intptr_t)pop(f); float fb,fa; memcpy(&fb,&bb,sizeof(fb)); memcpy(&fa,&aa,sizeof(fa)); float r=fa*fb; int rb; memcpy(&rb,&r,sizeof(rb)); push(f,(void*)(intptr_t)rb); break; }
            case 0x6b: { long bb=(long)(intptr_t)pop(f); long aa=(long)(intptr_t)pop(f); double db,da; memcpy(&db,&bb,sizeof(db)); memcpy(&da,&aa,sizeof(da)); double r=da*db; long rb; memcpy(&rb,&r,sizeof(rb)); push(f,(void*)(intptr_t)rb); break; }
            case 0x6d: { long b=(long)(intptr_t)pop(f); long a=(long)(intptr_t)pop(f); push(f,(void*)(intptr_t)(b==0?0:(b==-1?(long)(0ul-(unsigned long)a):a/b))); break; }
            case 0x6e: { float b=S2F(pop(f)); float a=S2F(pop(f)); push(f,F2S(a/b)); break; }
            case 0x6f: { double b=S2D(pop(f)); double a=S2D(pop(f)); push(f,D2S(a/b)); break; }
            case 0x71: { long b=(long)(intptr_t)pop(f); long a=(long)(intptr_t)pop(f); push(f,(void*)(intptr_t)(b==0?0:(b==-1?0:a%b))); break; }
            case 0x72: { float b=S2F(pop(f)); float a=S2F(pop(f)); push(f,F2S(fmodf(a,b))); break; }
            case 0x73: { double b=S2D(pop(f)); double a=S2D(pop(f)); push(f,D2S(fmod(a,b))); break; }
            case 0x75: { long a=(long)(intptr_t)pop(f); push(f,(void*)(intptr_t)(-a)); break; }
            case 0x76: { int a=(int)(intptr_t)pop(f); float fa; memcpy(&fa,&a,sizeof(fa)); float r=-fa; int rb; memcpy(&rb,&r,sizeof(rb)); push(f,(void*)(intptr_t)rb); break; }
            case 0x77: { long a=(long)(intptr_t)pop(f); double da; memcpy(&da,&a,sizeof(da)); double r=-da; long rb; memcpy(&rb,&r,sizeof(rb)); push(f,(void*)(intptr_t)rb); break; }
            case 0x78: { int sh=(int)(intptr_t)pop(f); int v=(int)(intptr_t)pop(f); push(f,(void*)(intptr_t)(int)((unsigned int)v<<(sh&31))); break; }
            case 0x7a: { int sh=(int)(intptr_t)pop(f); int v=(int)(intptr_t)pop(f); push(f,(void*)(intptr_t)(v>>(sh&31))); break; }
            case 0x80: { int b=(int)(intptr_t)pop(f); int a=(int)(intptr_t)pop(f); push(f,(void*)(intptr_t)(a|b)); break; }
            case 0x79: { int s=(int)(intptr_t)pop(f); long v=(long)(intptr_t)pop(f); push(f,(void*)(intptr_t)(long)((unsigned long)v<<(s&63))); break; }
            case 0x7b: { int s=(int)(intptr_t)pop(f); long v=(long)(intptr_t)pop(f); push(f,(void*)(intptr_t)(v>>(s&63))); break; }
            case 0x7c: { int s=(int)(intptr_t)pop(f); int v=(int)(intptr_t)pop(f); push(f,(void*)(intptr_t)((int)((unsigned int)v>>(s&31)))); break; }
            case 0x7d: { int s=(int)(intptr_t)pop(f); long v=(long)(intptr_t)pop(f); push(f,(void*)(intptr_t)((unsigned long)v>>(s&63))); break; }
            case 0x7e: { int b=(int)(intptr_t)pop(f); int a=(int)(intptr_t)pop(f); push(f,(void*)(intptr_t)(a&b)); break; }
            case 0x7f: { long b=(long)(intptr_t)pop(f); long a=(long)(intptr_t)pop(f); push(f,(void*)(intptr_t)(a&b)); break; }
            case 0x81: { long b=(long)(intptr_t)pop(f); long a=(long)(intptr_t)pop(f); push(f,(void*)(intptr_t)(a|b)); break; }
            case 0x82: { int b=(int)(intptr_t)pop(f); int a=(int)(intptr_t)pop(f); push(f,(void*)(intptr_t)(a^b)); break; }
            case 0x83: { long b=(long)(intptr_t)pop(f); long a=(long)(intptr_t)pop(f); push(f,(void*)(intptr_t)(a^b)); break; }
            /* comparisons */
            case 0x94: { long b=(long)(intptr_t)pop(f); long a=(long)(intptr_t)pop(f); int r = (a>b)?1:((a<b)?-1:0); push(f,(void*)(intptr_t)r); break; }
            case 0x95: { int b=(int)(intptr_t)pop(f); int a=(int)(intptr_t)pop(f); float fb,fa; memcpy(&fb,&b,sizeof(fb)); memcpy(&fa,&a,sizeof(fa)); int r; if (fa>fb) r=1; else if (fa<fb) r=-1; else if (fa==fb) r=0; else r=-1; push(f,(void*)(intptr_t)r); break; }
            case 0x96: { int b=(int)(intptr_t)pop(f); int a=(int)(intptr_t)pop(f); float fb,fa; memcpy(&fb,&b,sizeof(fb)); memcpy(&fa,&a,sizeof(fa)); int r; if (fa>fb) r=1; else if (fa<fb) r=-1; else if (fa==fb) r=0; else r=1; push(f,(void*)(intptr_t)r); break; }
            case 0x97: { long b=(long)(intptr_t)pop(f); long a=(long)(intptr_t)pop(f); double db,da; memcpy(&db,&b,sizeof(db)); memcpy(&da,&a,sizeof(da)); int r; if (da>db) r=1; else if (da<db) r=-1; else if (da==db) r=0; else r=-1; push(f,(void*)(intptr_t)r); break; }
            case 0x98: { long b=(long)(intptr_t)pop(f); long a=(long)(intptr_t)pop(f); double db,da; memcpy(&db,&b,sizeof(db)); memcpy(&da,&a,sizeof(da)); int r; if (da>db) r=1; else if (da<db) r=-1; else if (da==db) r=0; else r=1; push(f,(void*)(intptr_t)r); break; }
            /* conversions */
            case 0x85: { int v=(int)(intptr_t)pop(f); push(f,(void*)(intptr_t)(long)v); break; }
            case 0x86: { int v=(int)(intptr_t)pop(f); push(f,F2S((float)v)); break; }
            case 0x87: { int v=(int)(intptr_t)pop(f); push(f,D2S((double)v)); break; }
            case 0x88: { long v=(long)(intptr_t)pop(f); push(f,(void*)(intptr_t)(int)v); break; }
            case 0x89: { long v=(long)(intptr_t)pop(f); push(f,F2S((float)v)); break; }
            case 0x8a: { long v=(long)(intptr_t)pop(f); push(f,D2S((double)v)); break; }
            case 0x8b: { float v=S2F(pop(f)); push(f,(void*)(intptr_t)J_D2I((double)v)); break; }
            case 0x8c: { float v=S2F(pop(f)); push(f,(void*)(intptr_t)J_D2L((double)v)); break; }
            case 0x8d: { float v=S2F(pop(f)); push(f,D2S((double)v)); break; }
            case 0x8e: { double v=S2D(pop(f)); push(f,(void*)(intptr_t)J_D2I(v)); break; }
            case 0x8f: { double v=S2D(pop(f)); push(f,(void*)(intptr_t)J_D2L(v)); break; }
            case 0x90: { double v=S2D(pop(f)); push(f,F2S((float)v)); break; }
            case 0x84: { int idx=m->code[f->pc++]; int8_t inc=(int8_t)m->code[f->pc++]; f->locals[idx]=(void*)(intptr_t)((int)(intptr_t)f->locals[idx]+inc); break; }
            case 0x91: { int v=(int)(intptr_t)pop(f); push(f,(void*)(intptr_t)(signed char)v); break; }
            case 0x92: { int v=(int)(intptr_t)pop(f); push(f,(void*)(intptr_t)(unsigned short)v); break; }
            case 0x93: { int v=(int)(intptr_t)pop(f); push(f,(void*)(intptr_t)(short)v); break; }
            case 0x99: case 0x9a: case 0x9b: case 0x9c: case 0x9d: case 0x9e: {
                int16_t off = (int16_t)((m->code[f->pc] << 8) | m->code[f->pc+1]); f->pc += 2;
                int v = (int)(intptr_t)pop(f);
                if (op==0x99 && v==0) f->pc += off-3;
                if (op==0x9a && v!=0) f->pc += off-3;
                if (op==0x9b && v<0) f->pc += off-3;
                if (op==0x9c && v>=0) f->pc += off-3;
                if (op==0x9d && v>0) f->pc += off-3;
                if (op==0x9e && v<=0) f->pc += off-3;
                break;
            }
            case 0x9f: case 0xa0: case 0xa1: case 0xa2: case 0xa3: case 0xa4: {
                int16_t off = (int16_t)((m->code[f->pc] << 8) | m->code[f->pc+1]); f->pc += 2;
                int b=(int)(intptr_t)pop(f); int a=(int)(intptr_t)pop(f);
                int br=0;
                if (op==0x9f && a==b) br=1;
                if (op==0xa0 && a!=b) br=1;
                if (op==0xa1 && a<b) br=1;
                if (op==0xa2 && a>=b) br=1;
                if (op==0xa3 && a>b) br=1;
                if (op==0xa4 && a<=b) br=1;
                if (br) f->pc += off-3;
                break;
            }
            case 0xa5: case 0xa6: case 0xc6: case 0xc7: { /* ifnull/ifnonnull */
                int16_t off = (int16_t)((m->code[f->pc] << 8) | m->code[f->pc+1]); f->pc += 2;
                void *v = pop(f);
                int isnull_op = (op == 0xa5 || op == 0xc6);
                int br = isnull_op ? (v==NULL) : (v!=NULL);
                if (br) f->pc += off-3;
                break;
            }
            case 0xa7: { int16_t off=(int16_t)((m->code[f->pc]<<8)|m->code[f->pc+1]); f->pc+=2; f->pc += off-3; break; }
            case 0xaa: { /* tableswitch */
                int ts_base = f->pc - 1; /* address of tableswitch opcode */
                /* padding to 4-byte boundary relative to method start */
                while (f->pc % 4 != 0) f->pc++;
                /* read default, low, high */
                int default_off = (int)((m->code[f->pc]<<24)|(m->code[f->pc+1]<<16)|(m->code[f->pc+2]<<8)|m->code[f->pc+3]); f->pc += 4;
                int low = (int)((m->code[f->pc]<<24)|(m->code[f->pc+1]<<16)|(m->code[f->pc+2]<<8)|m->code[f->pc+3]); f->pc += 4;
                int high = (int)((m->code[f->pc]<<24)|(m->code[f->pc+1]<<16)|(m->code[f->pc+2]<<8)|m->code[f->pc+3]); f->pc += 4;
                int idx = (int)(intptr_t)pop(f);
                int jump;
                if (idx < low || idx > high) jump = default_off;
                else {
                    int off_idx = idx - low;
                    /* skip to the offset entry */
                    int entry_pc = f->pc + off_idx * 4;
                    jump = (int)((m->code[entry_pc]<<24)|(m->code[entry_pc+1]<<16)|(m->code[entry_pc+2]<<8)|m->code[entry_pc+3]);
                    f->pc += (high - low + 1) * 4;
                }
                f->pc = ts_base + jump;
                break;
            }
            case 0xab: { /* lookupswitch */
                int ts_base = f->pc - 1;
                while (f->pc % 4 != 0) f->pc++;
                int default_off = (int)((m->code[f->pc]<<24)|(m->code[f->pc+1]<<16)|(m->code[f->pc+2]<<8)|m->code[f->pc+3]); f->pc += 4;
                int npairs = (int)((m->code[f->pc]<<24)|(m->code[f->pc+1]<<16)|(m->code[f->pc+2]<<8)|m->code[f->pc+3]); f->pc += 4;
                int key = (int)(intptr_t)pop(f);
                int jump = default_off;
                for (int i = 0; i < npairs; i++) {
                    int k = (int)((m->code[f->pc]<<24)|(m->code[f->pc+1]<<16)|(m->code[f->pc+2]<<8)|m->code[f->pc+3]); f->pc += 4;
                    int off = (int)((m->code[f->pc]<<24)|(m->code[f->pc+1]<<16)|(m->code[f->pc+2]<<8)|m->code[f->pc+3]); f->pc += 4;
                    if (k == key) { jump = off; break; }
                }
                f->pc = ts_base + jump;
                break;
            }
            case 0xac: case 0xad: case 0xae: case 0xaf: case 0xb0: { result = pop(f); running=0; break; }
            case 0xb1: { running=0; break; }
            case 0xb2: {
                int idx = (m->code[f->pc] << 8) | m->code[f->pc+1]; f->pc += 2;
                void *val = NULL; int resolved = 0;
                if (m->const_pool != NULL && idx > 0 && idx < m->const_pool->count) {
                    nabu_cp_entry *e = &m->const_pool->entries[idx];
                    if (e->tag == 9) {
                        char *cname = nabu_cp_class_name(m->const_pool, e->ref_class);
                        int nat = e->ref_nat;
                        char *fname = (nat > 0) ? nabu_cp_utf8(m->const_pool, m->const_pool->entries[nat].nat_name) : NULL;
                        if (cname && fname && strcmp(cname, "java/lang/System") == 0 && strcmp(fname, "out") == 0) {
                            val = (void *)0x570D007; resolved = 1;
                        } else if (cname && fname && strcmp(cname, "java/lang/Boolean") == 0 && (strcmp(fname, "TRUE") == 0 || strcmp(fname, "FALSE") == 0)) {
                            val = nabu_box_bool(fname[0] == 'T'); resolved = 1;
                        } else if (cname && fname && strcmp(fname, "TYPE") == 0 && primitive_of_wrapper(cname) != NULL) {
                            val = nabu_class_object_for_name(primitive_of_wrapper(cname)); resolved = 1;
                        } else if (cname && fname) {
                            ensure_clinit(cname);
                            nabu_static_t *sl = static_slot(cname, fname, 0);
                            val = sl != NULL ? sl->value : NULL; resolved = 1;
                        }
                    }
                }
                if (!resolved) val = (void *)(intptr_t)0;
                push(f, val);
                break;
            }
            case 0xb3: {
                int idx = (m->code[f->pc] << 8) | m->code[f->pc+1]; f->pc += 2;
                void *val = pop(f);
                if (m->const_pool != NULL && idx > 0 && idx < m->const_pool->count) {
                    nabu_cp_entry *e = &m->const_pool->entries[idx];
                    if (e->tag == 9) {
                        char *cname = nabu_cp_class_name(m->const_pool, e->ref_class);
                        int nat = e->ref_nat;
                        char *fname = (nat > 0) ? nabu_cp_utf8(m->const_pool, m->const_pool->entries[nat].nat_name) : NULL;
                        if (cname && fname) {
                            if (strcmp(m->name, "<clinit>") != 0 || strcmp(m->class_name, cname) != 0) ensure_clinit(cname);
                            nabu_static_t *sl = static_slot(cname, fname, 1);
                            if (sl != NULL) sl->value = val;
                        }
                    }
                }
                break;
            }
            case 0xb4: {
                int idx = (m->code[f->pc] << 8) | m->code[f->pc+1]; f->pc += 2;
                void *obj = pop(f);
                void *val = NULL;
                if (obj == NULL && strict_nulls(m)) { interp_throw_array(1); if (interp_jmp_active) longjmp(interp_jmp_buf, 1); running = 0; break; }
                if (obj != NULL && m->const_pool != NULL && idx > 0 && idx < m->const_pool->count) {
                    nabu_cp_entry *e = &m->const_pool->entries[idx];
                    if (e->tag == 9) {
                        char *cname = nabu_cp_class_name(m->const_pool, e->ref_class);
                        int nat = e->ref_nat;
                        char *fname = (nat > 0) ? nabu_cp_utf8(m->const_pool, m->const_pool->entries[nat].nat_name) : NULL;
                        if (cname && fname) {
                            int fi = nabu_interp_field_index(cname, fname);
                            if (fi >= 0) val = ((void **)((char *)obj + sizeof(nabu_object)))[fi];
                        }
                    }
                }
                push(f, val);
                break;
            }
            case 0xb5: {
                int idx = (m->code[f->pc] << 8) | m->code[f->pc+1]; f->pc += 2;
                void *val = pop(f);
                void *obj = pop(f);
                if (obj == NULL && strict_nulls(m)) { interp_throw_array(1); if (interp_jmp_active) longjmp(interp_jmp_buf, 1); running = 0; break; }
                if (obj != NULL && m->const_pool != NULL && idx > 0 && idx < m->const_pool->count) {
                    nabu_cp_entry *e = &m->const_pool->entries[idx];
                    if (e->tag == 9) {
                        char *cname = nabu_cp_class_name(m->const_pool, e->ref_class);
                        int nat = e->ref_nat;
                        char *fname = (nat > 0) ? nabu_cp_utf8(m->const_pool, m->const_pool->entries[nat].nat_name) : NULL;
                        if (cname && fname) {
                            int fi = nabu_interp_field_index(cname, fname);
                            if (fi >= 0) ((void **)((char *)obj + sizeof(nabu_object)))[fi] = val;
                        }
                    }
                }
                break;
            }
            case 0xb6: case 0xb7: case 0xb8: {
                int idx = (m->code[f->pc] << 8) | m->code[f->pc+1]; f->pc += 2;
                char *proxy_method_name = NULL;
                if (m->const_pool != NULL && idx > 0 && idx < m->const_pool->count) {
                    nabu_cp_entry *pe = &m->const_pool->entries[idx];
                    if (pe->tag == 9 || pe->tag == 10 || pe->tag == 11) {
                        int nat_idx = pe->ref_nat;
                        if (nat_idx > 0) proxy_method_name = nabu_cp_utf8(m->const_pool, m->const_pool->entries[nat_idx].nat_name);
                    }
                }
                int is_static_early = (op == 0xb8);
                /* Lambda closure dispatch: scan top slots for a closure receiver. */
                if (!is_static_early && f->stack_top > 0) {
                    nabu_closure_t *cl = NULL;
                    int sam_params = 0;
                    for (int k = 0; k < 8 && k < f->stack_top; k++) {
                        nabu_closure_t *cc = nabu_as_closure(f->stack[f->stack_top - 1 - k]);
                        if (cc != NULL) { cl = cc; sam_params = k; break; }
                    }
                    if (cl != NULL) {
                        if (sam_params < 0) sam_params = 0;
                        void **sam_args = (void **)malloc(sizeof(void *) * (sam_params > 0 ? sam_params : 1));
                        for (int i = sam_params - 1; i >= 0; i--) sam_args[i] = pop(f);
                        pop(f); /* receiver (closure) */
                        void *r = call_closure(cl, sam_args, sam_params);
                        free(sam_args);
                        if (interp_pending_exception != NULL) {
                            if (interp_jmp_active) longjmp(interp_jmp_buf, 1);
                        }
                        if (!is_void_return(cl->impl_desc)) push(f, r);
                        break;
                    }
                }
                /* Proxy dispatch (0xb6 path) */
                if (!is_static_early && f->stack_top > 0) {
                    void *proxy_recv = NULL;
                    int proxy_sam = 0;
                    for (int k = 0; k < 8 && k < f->stack_top; k++) {
                        if (nabu_is_proxy(f->stack[f->stack_top - 1 - k]) && !(proxy_method_name != NULL && strcmp(proxy_method_name, "getClass") == 0)) { proxy_recv = f->stack[f->stack_top - 1 - k]; proxy_sam = k; break; }
                    }
                    if (proxy_recv != NULL) {
                        void *handler = nabu_proxy_handler(proxy_recv);
                        void **sam_args = (void **)malloc(sizeof(void *) * (proxy_sam > 0 ? proxy_sam : 1));
                        for (int i = proxy_sam - 1; i >= 0; i--) sam_args[i] = pop(f);
                        pop(f); /* proxy receiver */
                        char *mn = proxy_method_name ? proxy_method_name : "greet";
                        long a0 = (proxy_sam > 0) ? (long)(intptr_t)sam_args[0] : 0;
                        long a1 = (proxy_sam > 1) ? (long)(intptr_t)sam_args[1] : 0;
                        const char *hcls = handler ? nabu_compiled_type_name(handler) : NULL;
                        if (hcls == NULL) hcls = "java/lang/Object";
                        char *desc = "(Ljava/lang/String;JJ)Ljava/lang/String;";
                        nabu_interp_method_t *hm = nabu_interp_find_method(hcls, "invokeMethod", desc);
                        void *r = NULL;
                        if (hm != NULL) {
                            void *cargs[4] = { handler, (void *)strdup(mn), (void *)(intptr_t)a0, (void *)(intptr_t)a1 };
                            r = nabu_interp_invoke(hm, cargs, 4);
                        } else {
                            void *nf = nabu_find_native(hcls, "invokeMethod", desc);
                            if (nf != NULL) {
                                nabu_interp_method_t *nm = (nabu_interp_method_t *)calloc(1, sizeof(nabu_interp_method_t));
                                nm->class_name = strdup(hcls);
                                nm->name = strdup("invokeMethod");
                                nm->desc = strdup(desc);
                                nm->is_native = 1;
                                nm->native_fn = nf;
                                nm->max_locals = 8; nm->max_stack = 8;
                                void *cargs[4] = { handler, (void *)strdup(mn), (void *)(intptr_t)a0, (void *)(intptr_t)a1 };
                                r = nabu_interp_invoke(nm, cargs, 4);
                            }
                        }
                        free(sam_args);
                        push(f, r);
                        break;
                    }
                }
                /* String-concat invokedynamic is handled in 0xba; here normal invoke. */
                nabu_interp_method_t *target = nabu_interp_method_by_id(idx);

                if (target == NULL && m->const_pool != NULL && idx > 0 && idx < m->const_pool->count) {
                    nabu_cp_entry *e = &m->const_pool->entries[idx];
                    if (e->tag == 10 || e->tag == 11 || e->tag == 9) {
                        int _ci = e->ref_class; int _ni = e->ref_nat;
                        char *_cn = (_ci>0)?nabu_cp_class_name(m->const_pool,_ci):NULL;
                        char *_mn = (_ni>0)?nabu_cp_utf8(m->const_pool, m->const_pool->entries[_ni].nat_name):NULL;
                        char *_md = (_ni>0)?nabu_cp_utf8(m->const_pool, m->const_pool->entries[_ni].nat_desc):NULL;


                        int cls_idx = e->ref_class;
                        int nat_idx = e->ref_nat;
                        char *cname = (cls_idx > 0) ? nabu_cp_class_name(m->const_pool, cls_idx) : NULL;
                        char *mname = NULL, *mdesc = NULL;
                        if (nat_idx > 0) {
                            mname = nabu_cp_utf8(m->const_pool, m->const_pool->entries[nat_idx].nat_name);
                            mdesc = nabu_cp_utf8(m->const_pool, m->const_pool->entries[nat_idx].nat_desc);
                        }





                        void *nf = (cname && mname && mdesc) ? nabu_find_native(cname, mname, mdesc) : NULL;
                        if (nf != NULL) {
                            nabu_interp_method_t *nm = (nabu_interp_method_t *)calloc(1, sizeof(nabu_interp_method_t));
                            nm->class_name = cname ? strdup(cname) : strdup("?");
                            nm->name = mname ? strdup(mname) : strdup("?");
                            nm->desc = mdesc ? strdup(mdesc) : strdup("?");
                            nm->is_native = 1;
                            nm->native_fn = nf;
                            nm->max_locals = 8; nm->max_stack = 8;
                            target = nm;
                        } else if (cname && mname && mdesc) {
                            target = nabu_interp_find_method(cname, mname, mdesc);
                        }
                        /* Auto-load: if not found, try loading the class from classpath. */
                        if (target == NULL && cname != NULL && mname != NULL && mdesc != NULL) {

                            if (nabu_classfile_load_class(cname) == 0) {
                                void *nf2 = nabu_find_native(cname, mname, mdesc);
                                if (nf2 != NULL) {
                                    nabu_interp_method_t *nm = (nabu_interp_method_t *)calloc(1, sizeof(nabu_interp_method_t));
                                    nm->class_name = strdup(cname);
                                    nm->name = strdup(mname);
                                    nm->desc = strdup(mdesc);
                                    nm->is_native = 1;
                                    nm->native_fn = nf2;
                                    nm->max_locals = 8; nm->max_stack = 8;
                                    target = nm;
                                } else {
                                    target = nabu_interp_find_method(cname, mname, mdesc);
                                }
                            }
                        }
                    }
                }
                if ((op == 0xb6 || op == 0xb7) && m->const_pool != NULL && idx > 0 && idx < m->const_pool->count) {
                    target = resolve_invoke_target(f, m->const_pool, idx, op, target);
                }
                if (target == NULL) {
                    {
                        const char *dc = "?", *dn = "?", *dd = "?";
                        if (m->const_pool != NULL && idx > 0 && idx < m->const_pool->count) {
                            nabu_cp_entry *de = &m->const_pool->entries[idx];
                            if ((de->tag == 10 || de->tag == 11 || de->tag == 9) && de->ref_class > 0 && de->ref_nat > 0) {
                                char *c1 = nabu_cp_class_name(m->const_pool, de->ref_class);
                                char *n1 = nabu_cp_utf8(m->const_pool, m->const_pool->entries[de->ref_nat].nat_name);
                                char *d1 = nabu_cp_utf8(m->const_pool, m->const_pool->entries[de->ref_nat].nat_desc);
                                if (c1) dc = c1;
                                if (n1) dn = n1;
                                if (d1) dd = d1;
                            }
                        }
                        fprintf(stderr, "interp: unknown method %s.%s%s (cp %d) called from %s.%s%s\n", dc, dn, dd, idx,
                                m->class_name ? m->class_name : "?", m->name ? m->name : "?", m->desc ? m->desc : "");
                    }
                    /* Maintain stack balance. */
                    char *mdesc2 = NULL;
                    int pcnt2 = 0;
                    int is_static2 = (op == 0xb8);
                    if (m->const_pool != NULL && idx > 0 && idx < m->const_pool->count) {
                        nabu_cp_entry *e2 = &m->const_pool->entries[idx];
                        if (e2->tag == 10 || e2->tag == 11 || e2->tag == 9) {
                            int nat_idx2 = e2->ref_nat;
                            if (nat_idx2 > 0) mdesc2 = nabu_cp_utf8(m->const_pool, m->const_pool->entries[nat_idx2].nat_desc);
                        }
                    }
                    if (mdesc2 != NULL) pcnt2 = count_params(mdesc2);
                    for (int i = 0; i < pcnt2; i++) pop(f);
                    if (!is_static2 && f->stack_top > 0) pop(f);
                    break;
                }
                int pcnt = count_params(target->desc);
                int is_static = (op == 0xb8);
                int nargs = pcnt + (is_static ? 0 : 1);
                void **args = (void **)malloc(sizeof(void *) * (nargs > 0 ? nargs : 1));
                for (int i = pcnt - 1; i >= 0; i--) {
                    args[is_static ? i : i + 1] = pop(f);
                }
                if (!is_static) args[0] = pop(f);
                if (!is_static && args[0] == NULL && strict_nulls(m) && strcmp(target->name, "<init>") != 0) {
                    free(args);
                    interp_throw_array(1);
                    if (interp_jmp_active) longjmp(interp_jmp_buf, 1);
                    running = 0; break;
                }
                void *r = nabu_interp_invoke(target, args, nargs);
                free(args);
                if (interp_pending_exception != NULL) {
                    if (interp_jmp_active) longjmp(interp_jmp_buf, 1);
                }
                if (!is_void_return(target->desc)) {
                    push(f, r);
                }
                break;
            }
            case 0xb9: { /* invokeinterface */
                int idx = (m->code[f->pc] << 8) | m->code[f->pc+1]; f->pc += 4;
                /* Extract method name from CP for proxy dispatch. */
                char *iface_name = NULL;
                char *method_name = NULL;
                if (m->const_pool != NULL && idx > 0 && idx < m->const_pool->count) {
                    nabu_cp_entry *e = &m->const_pool->entries[idx];
                    if (e->tag == 9 || e->tag == 10 || e->tag == 11) {
                        int cls_idx = e->ref_class;
                        int nat_idx = e->ref_nat;
                        if (cls_idx > 0) iface_name = nabu_cp_class_name(m->const_pool, cls_idx);
                        if (nat_idx > 0) method_name = nabu_cp_utf8(m->const_pool, m->const_pool->entries[nat_idx].nat_name);
                    }
                }
                /* Lambda closure dispatch: scan top slots. */
                if (f->stack_top > 0) {
                    nabu_closure_t *cl = NULL;
                    int sam_params = 0;
                    for (int k = 0; k < 8 && k < f->stack_top; k++) {
                        nabu_closure_t *cc = nabu_as_closure(f->stack[f->stack_top - 1 - k]);
                        if (cc != NULL) { cl = cc; sam_params = k; break; }
                    }
                    if (cl != NULL) {
                        int impl_pcnt = count_params(cl->impl_desc);
                        if (sam_params < 0) sam_params = 0;
                        void **sam_args = (void **)malloc(sizeof(void *) * (sam_params > 0 ? sam_params : 1));
                        for (int i = sam_params - 1; i >= 0; i--) sam_args[i] = pop(f);
                        pop(f); /* receiver */
                        int total = cl->captured_count + sam_params;
                        void **args = (void **)malloc(sizeof(void *) * (total > 0 ? total : 1));
                        /* Detect instance method ref. */
                        int is_instance = (count_params(cl->impl_desc) + 1 == (cl->captured_count + sam_params)) && (cl->captured_count + sam_params > 0);
                        if (is_instance) {
                            void *receiver;
                            int pi = 0;
                            void **params = (void **)malloc(sizeof(void *) * (cl->captured_count + sam_params));
                            if (cl->captured_count > 0) {
                                receiver = cl->captured[0];
                                for (int i = 1; i < cl->captured_count; i++) params[pi++] = cl->captured[i];
                                for (int i = 0; i < sam_params; i++) params[pi++] = sam_args[i];
                            } else {
                                receiver = (sam_params > 0) ? sam_args[0] : NULL;
                                for (int i = 1; i < sam_params; i++) params[pi++] = sam_args[i];
                            }
                            args[0] = receiver;
                            for (int i = 0; i < pi; i++) args[i + 1] = params[i];
                            free(params);
                        } else {
                            for (int i = 0; i < cl->captured_count; i++) args[i] = cl->captured[i];
                            for (int i = 0; i < sam_params; i++) args[cl->captured_count + i] = sam_args[i];
                        }
                        free(sam_args);
                        nabu_interp_method_t *target = nabu_interp_find_method(cl->impl_class, cl->impl_name, cl->impl_desc);
                        if (target == NULL) {
                            void *nf = nabu_find_native(cl->impl_class, cl->impl_name, cl->impl_desc);
                            if (nf != NULL) {
                                nabu_interp_method_t *nm = (nabu_interp_method_t *)calloc(1, sizeof(nabu_interp_method_t));
                                nm->class_name = strdup(cl->impl_class);
                                nm->name = strdup(cl->impl_name);
                                nm->desc = strdup(cl->impl_desc);
                                nm->is_native = 1;
                                nm->native_fn = nf;
                                nm->max_locals = 8; nm->max_stack = 8;
                                target = nm;
                            }
                        }
                        void *r = NULL;
                        if (target != NULL) r = nabu_interp_invoke(target, args, total);
                        else fprintf(stderr, "interp: closure impl not found %s.%s%s\n", cl->impl_class, cl->impl_name, cl->impl_desc);
                        free(args);
                        if (interp_pending_exception != NULL) { if (interp_jmp_active) longjmp(interp_jmp_buf, 1); }
                        if (!is_void_return(cl->impl_desc)) push(f, r);
                        break;
                    }
                }
                /* Proxy dispatch */
                if (f->stack_top > 0) {
                    char *proxy_method_name = NULL;
                    if (m->const_pool != NULL && idx > 0 && idx < m->const_pool->count) {
                        nabu_cp_entry *pe = &m->const_pool->entries[idx];
                        if ((pe->tag == 9 || pe->tag == 10 || pe->tag == 11) && pe->ref_nat > 0)
                            proxy_method_name = nabu_cp_utf8(m->const_pool, m->const_pool->entries[pe->ref_nat].nat_name);
                    }
                    void *proxy_recv = NULL;
                    int proxy_sam = 0;
                    for (int k = 0; k < 8 && k < f->stack_top; k++) {
                        if (nabu_is_proxy(f->stack[f->stack_top - 1 - k]) && !(proxy_method_name != NULL && strcmp(proxy_method_name, "getClass") == 0)) { proxy_recv = f->stack[f->stack_top - 1 - k]; proxy_sam = k; break; }
                    }
                     if (proxy_recv != NULL) {
                         void *handler = nabu_proxy_handler(proxy_recv);
                         void **sam_args = (void **)malloc(sizeof(void *) * (proxy_sam > 0 ? proxy_sam : 1));
                         for (int i = proxy_sam - 1; i >= 0; i--) sam_args[i] = pop(f);
                         pop(f); /* proxy receiver */
                         char *mn = method_name ? method_name : "invoke";
                         const char *hcls = handler ? nabu_compiled_type_name(handler) : NULL;
                         if (hcls == NULL) hcls = "java/lang/Object";
                         /* Create a Method object for the invoked method. */
                         nabu_classfile_load_class("java/lang/reflect/Method");
                         int mfc = nabu_interp_field_count("java/lang/reflect/Method");
                         void *methodObj = calloc(1, sizeof(nabu_object) + (size_t)(mfc > 0 ? mfc : 1) * sizeof(void *));
                         if (methodObj != NULL) {
                             nabu_type_info *mti = (nabu_type_info *)nabu_register_interpreted_type("java/lang/reflect/Method", "java/lang/Object", 0, NULL, NULL);
                             ((nabu_object *)methodObj)->type = mti;
                             int fi_name = nabu_interp_field_index("java/lang/reflect/Method", "name");
                             if (fi_name >= 0) {
                                 void **mfields = (void **)((char *)methodObj + sizeof(nabu_object));
                                 mfields[fi_name] = (void *)strdup(mn);
                             }
                             nabu_register_method_info(methodObj, iface_name ? iface_name : "java/lang/Object", mn, "()Ljava/lang/Object;");
                         }
                         /* Create Object[] args array. */
                         void *argsArr = calloc(1, 8 + (size_t)(proxy_sam > 0 ? proxy_sam : 1) * sizeof(void *));
                         if (argsArr != NULL) *(int *)argsArr = proxy_sam;
                         void **argElems = (void **)((char *)argsArr + 8);
                         for (int i = 0; i < proxy_sam; i++) argElems[i] = sam_args[i];
                         /* Try handler.invoke(proxy, method, args) -- JDK protocol. */
                         char *invokeDesc = "(Ljava/lang/Object;Ljava/lang/reflect/Method;[Ljava/lang/Object;)Ljava/lang/Object;";
                         nabu_interp_method_t *hm = nabu_interp_find_method(hcls, "invoke", invokeDesc);
                         void *r = NULL;
                         int invoked = 0;
                         nabu_closure_t *hcl = nabu_as_closure(handler);
                         if (hcl != NULL) {
                             /* a lambda as InvocationHandler */
                             void *hargs[3] = { proxy_recv, methodObj, argsArr };
                             r = call_closure(hcl, hargs, 3);
                             invoked = 1;
                         } else if (hm != NULL) {
                             void *cargs[4] = { handler, proxy_recv, methodObj, argsArr };
                             r = nabu_interp_invoke(hm, cargs, 4);
                             invoked = 1;
                         } else {
                             void *nf = nabu_find_native(hcls, "invoke", invokeDesc);
                             if (nf != NULL) {
                                 nabu_interp_method_t *nm = (nabu_interp_method_t *)calloc(1, sizeof(nabu_interp_method_t));
                                 nm->class_name = strdup(hcls);
                                 nm->name = strdup("invoke");
                                 nm->desc = strdup(invokeDesc);
                                 nm->is_native = 1;
                                 nm->native_fn = nf;
                                 nm->max_locals = 8; nm->max_stack = 8;
                                 void *cargs[4] = { handler, proxy_recv, methodObj, argsArr };
                                 r = nabu_interp_invoke(nm, cargs, 4);
                                 invoked = 1;
                             }
                         }
                         if (!invoked) {
                             /* Fall back to invokeMethod shim. */
                             char *desc = "(Ljava/lang/String;JJ)J";
                             nabu_interp_method_t *hm2 = nabu_interp_find_method(hcls, "invokeMethod", desc);
                             long a0 = (proxy_sam > 0) ? (long)(intptr_t)sam_args[0] : 0;
                             long a1 = (proxy_sam > 1) ? (long)(intptr_t)sam_args[1] : 0;
                             if (hm2 != NULL) {
                                 void *cargs[4] = { handler, (void *)strdup(mn), (void *)(intptr_t)a0, (void *)(intptr_t)a1 };
                                 r = nabu_interp_invoke(hm2, cargs, 4);
                             } else {
                                 void *nf2 = nabu_find_native(hcls, "invokeMethod", desc);
                                 if (nf2 != NULL) {
                                     nabu_interp_method_t *nm2 = (nabu_interp_method_t *)calloc(1, sizeof(nabu_interp_method_t));
                                     nm2->class_name = strdup(hcls);
                                     nm2->name = strdup("invokeMethod");
                                     nm2->desc = strdup(desc);
                                     nm2->is_native = 1;
                                     nm2->native_fn = nf2;
                                     nm2->max_locals = 8; nm2->max_stack = 8;
                                     void *cargs[4] = { handler, (void *)strdup(mn), (void *)(intptr_t)a0, (void *)(intptr_t)a1 };
                                     r = nabu_interp_invoke(nm2, cargs, 4);
                                 } else {
                                     fprintf(stderr, "proxy: no invoke/invokeMethod on %s\n", hcls);
                                 }
                             }
                         }
                         free(sam_args);
                         push(f, r);
                         break;
                     }
                }
                char *recv_type_for_cache = NULL;
                nabu_interp_method_t *target = NULL;
                if (m->const_pool != NULL && idx > 0 && idx < m->const_pool->count) {
                    nabu_cp_entry *e = &m->const_pool->entries[idx];
                    if (e->tag == 11 || e->tag == 10 || e->tag == 9) {
                        int cls_idx = e->ref_class;
                        int nat_idx = e->ref_nat;
                        char *cname = (cls_idx > 0) ? nabu_cp_class_name(m->const_pool, cls_idx) : NULL;
                        char *mname = NULL, *mdesc = NULL;
                        if (nat_idx > 0) {
                            mname = nabu_cp_utf8(m->const_pool, m->const_pool->entries[nat_idx].nat_name);
                            mdesc = nabu_cp_utf8(m->const_pool, m->const_pool->entries[nat_idx].nat_desc);
                        }




                        /* Formal itable cache lookup. */
                        if (cname && mname && mdesc) {
                            int _pcnt = count_params(mdesc);
                            if (f->stack_top >= _pcnt + 1) {
                                void *recv = f->stack[f->stack_top - _pcnt - 1];
                                recv_type_for_cache = (char *)nabu_compiled_type_name(recv);
                            }
                        }
                        nabu_itable_entry_t *cached = (recv_type_for_cache && cname && mname && mdesc)
                            ? itable_lookup(recv_type_for_cache, cname, mname, mdesc) : NULL;
                        if (cached != NULL && cached->valid) {
                            /* Use cached result. */
                            if (cached->is_native && cached->native_fn != NULL) {
                                nabu_interp_method_t *nm = (nabu_interp_method_t *)calloc(1, sizeof(nabu_interp_method_t));
                                nm->class_name = strdup(cached->recv_type ? cached->recv_type : (cname ? cname : "?"));
                                nm->name = strdup(cached->mname ? cached->mname : (mname ? mname : "?"));
                                nm->desc = strdup(cached->mdesc ? cached->mdesc : (mdesc ? mdesc : "?"));
                                nm->is_native = 1;
                                nm->native_fn = cached->native_fn;
                                nm->max_locals = 8; nm->max_stack = 8;
                                target = nm;
                            } else if (cached->method != NULL) {
                                target = cached->method;
                            }
                        }
                        if (target == NULL) {
                        /* Interface dispatch: try receiver's actual type + superclasses. */
                        const char *recv_type = NULL;
                        nabu_type_info *recv_ti = NULL;
                        if (cname && mname && mdesc) {
                            int _pcnt = count_params(mdesc);
                            if (f->stack_top >= _pcnt + 1) {
                                void *recv = f->stack[f->stack_top - _pcnt - 1];
                                recv_type = nabu_compiled_type_name(recv);
                                if (recv != NULL) {
                                    /* get type info */
                                    nabu_object *o = (nabu_object *)recv;
                                    /* check if it's a nabu_object (has type at offset 0) */
                                    /* We'll try nabu_compiled_type_name already validated; get super via type_info if available */
                                    /* For interpreted objects, type is at offset 0 */
                                }
                            }
                        }
                        /* Build type chain: recv_type + superclasses. */
                        char *chain[8];
                        int chain_count = 0;
                        if (recv_type != NULL) {
                            /* Walk super_type chain via nabu_type_info. */
                            /* Get type info from receiver: first 8 bytes is type pointer for nabu_object. */
                            void *recv = NULL;
                            if (cname && mname && mdesc) {
                                int _pcnt = count_params(mdesc);
                                if (f->stack_top >= _pcnt + 1) recv = f->stack[f->stack_top - _pcnt - 1];
                            }
                            if (recv != NULL) {
                                nabu_type_info *ti = (nabu_type_info *)(((void **)recv)[0]);
                                /* Validate: ti->name should match recv_type */
                                while (ti != NULL && chain_count < 8) {
                                    if (ti->name != NULL) {
                                        int dup = 0;
                                        for (int d = 0; d < chain_count; d++) if (strcmp(chain[d], ti->name) == 0) { dup = 1; break; }
                                        if (!dup) chain[chain_count++] = (char *)ti->name;
                                    }
                                    ti = ti->super_type;
                                }
                            }
                        }
                        if (chain_count == 0 && recv_type != NULL) {
                            chain[chain_count++] = (char *)recv_type;
                        }
                        /* Add interface cname as fallback. */
                        if (cname != NULL) {
                            int dup = 0;
                            for (int d = 0; d < chain_count; d++) if (strcmp(chain[d], cname) == 0) { dup = 1; break; }
                            if (!dup && chain_count < 8) chain[chain_count++] = cname;
                        }
                        /* Try native find across chain. */
                        void *nf = NULL;
                        char *found_cls = NULL;
                        for (int t = 0; t < chain_count && nf == NULL; t++) {
                            nf = (chain[t] && mname && mdesc) ? nabu_find_native(chain[t], mname, mdesc) : NULL;
                            if (nf != NULL) found_cls = chain[t];
                        }
                        if (nf != NULL) {
                            nabu_interp_method_t *nm = (nabu_interp_method_t *)calloc(1, sizeof(nabu_interp_method_t));
                            nm->class_name = strdup(found_cls ? found_cls : (cname ? cname : "?"));
                            nm->name = mname ? strdup(mname) : strdup("?");
                            nm->desc = mdesc ? strdup(mdesc) : strdup("?");
                            nm->is_native = 1;
                            nm->native_fn = nf;
                            nm->max_locals = 8; nm->max_stack = 8;
                            target = nm;
                        } else if (cname && mname && mdesc) {
                            for (int t = 0; t < chain_count && target == NULL; t++) {
                                target = nabu_interp_find_method(chain[t], mname, mdesc);
                            }
                            if (target == NULL) target = nabu_interp_find_method(cname, mname, mdesc);
                        }
                        /* Insert into itable cache if resolved. */
                        if (target != NULL && recv_type_for_cache != NULL && cname && mname && mdesc) {
                            int is_native = target->is_native;
                            void *nf = is_native ? target->native_fn : NULL;
                            nabu_interp_method_t *meth = is_native ? NULL : target;
                            itable_insert(recv_type_for_cache, cname, mname, mdesc, meth, nf, is_native);
                        }
                        }  /* end itable-miss chain search */
                        /* Auto-load class if method not found. */
                        if (target == NULL && cname != NULL && mname != NULL && mdesc != NULL) {

                            if (nabu_classfile_load_class(cname) == 0) {
                                void *nf2 = nabu_find_native(cname, mname, mdesc);
                                if (nf2 != NULL) {
                                    nabu_interp_method_t *nm = (nabu_interp_method_t *)calloc(1, sizeof(nabu_interp_method_t));
                                    nm->class_name = strdup(cname);
                                    nm->name = strdup(mname);
                                    nm->desc = strdup(mdesc);
                                    nm->is_native = 1;
                                    nm->native_fn = nf2;
                                    nm->max_locals = 8; nm->max_stack = 8;
                                    target = nm;
                                } else {
                                    target = nabu_interp_find_method(cname, mname, mdesc);
                                }
                            }
                        }
                    }  /* end if (e->tag...) */
                }  /* end if (m->const_pool...) */
                if (target == NULL && m->const_pool != NULL && idx > 0 && idx < m->const_pool->count) {
                    /* An interface type has the methods of Object (getClass, hashCode, ...). */
                    nabu_cp_entry *oe = &m->const_pool->entries[idx];
                    if (oe->ref_nat > 0) {
                        char *on = nabu_cp_utf8(m->const_pool, m->const_pool->entries[oe->ref_nat].nat_name);
                        char *od = nabu_cp_utf8(m->const_pool, m->const_pool->entries[oe->ref_nat].nat_desc);
                        void *onf = (on && od) ? nabu_find_native("java/lang/Object", on, od) : NULL;
                        if (onf != NULL) {
                            nabu_interp_method_t *nm = (nabu_interp_method_t *)calloc(1, sizeof(nabu_interp_method_t));
                            nm->class_name = strdup("java/lang/Object");
                            nm->name = strdup(on);
                            nm->desc = strdup(od);
                            nm->is_native = 1;
                            nm->native_fn = onf;
                            nm->max_locals = 8; nm->max_stack = 8;
                            target = nm;
                        }
                    }
                }
                if (target == NULL) {
                    fprintf(stderr, "interp: unknown interface method idx %d\n", idx);
                    break;
                }
                int pcnt = count_params(target->desc);
                int nargs = pcnt + 1;
                void **args = (void **)malloc(sizeof(void *) * (nargs > 0 ? nargs : 1));
                for (int i = pcnt - 1; i >= 0; i--) args[i+1] = pop(f);
                args[0] = pop(f);
                void *r = nabu_interp_invoke(target, args, nargs);
                free(args);
                if (interp_pending_exception != NULL) {
                    if (interp_jmp_active) longjmp(interp_jmp_buf, 1);
                }
                if (!is_void_return(target->desc)) push(f, r);
                break;
            }
            case 0xba: { /* invokedynamic */
                int idx = (m->code[f->pc] << 8) | m->code[f->pc+1]; f->pc += 4;
                if (m->const_pool != NULL && idx > 0 && idx < m->const_pool->count) {
                    nabu_cp_entry *e = &m->const_pool->entries[idx];
                    if (e->tag == 18) {
                        int bs_idx = e->ref_class;
                        int nat_idx = e->ref_nat;
                        nabu_bootstrap_methods_t *bsm = m->const_pool->bootstrap_methods;
                        nabu_bootstrap_method_t *bm = (bsm != NULL && bs_idx < bsm->count) ? &bsm->methods[bs_idx] : NULL;
                        char *mname = (nat_idx > 0) ? nabu_cp_utf8(m->const_pool, m->const_pool->entries[nat_idx].nat_name) : NULL;
                        char *mdesc = (nat_idx > 0) ? nabu_cp_utf8(m->const_pool, m->const_pool->entries[nat_idx].nat_desc) : NULL;
                        /* Lambda? A static arg contains "MH:" (method handle). */
                        int is_lambda = 0;
                        if (bm != NULL) {
                            for (int k = 0; k < bm->num_static_args; k++) {
                                if (bm->static_args[k] != NULL && strstr(bm->static_args[k], "MH:") != NULL) { is_lambda = 1; break; }
                            }
                        }
                        /* String concat? Recipe contains \\u0001 placeholder. */
                        int is_concat = (!is_lambda && bm != NULL && bm->num_static_args > 0 && bm->static_args[0] != NULL &&
                                         strchr(bm->static_args[0], 1) != NULL);
                        if (is_concat) {
                            int pcnt = (mdesc != NULL) ? count_params(mdesc) : 0;
                            void **args = (void **)malloc(sizeof(void *) * (pcnt > 0 ? pcnt : 1));
                            for (int i = pcnt - 1; i >= 0; i--) args[i] = pop(f);
                            char *recipe = bm->static_args[0];
                            size_t cap = 64, len = 0;
                            char *res = (char *)malloc(cap);
                            res[0] = 0;
                            int argi = 0;
                            for (const char *rp = recipe; *rp; ) {
                                if ((unsigned char)*rp == 1) {
                                    char tmp[256];
                                    if (argi < pcnt) {
                                        /* Determine type from mdesc. */
                                        char t = 0;
                                        if (mdesc != NULL) {
                                            const char *pp = strchr(mdesc, '(');
                                            if (pp) {
                                                pp++;
                                                int idx2 = 0;
                                                const char *q = pp;
                                                while (idx2 < argi && *q != ')') {
                                                    if (*q == 'L') { while (*q != ';' && *q) q++; if (*q == ';') q++; }
                                                    else if (*q == '[') { while (*q == '[') q++; if (*q == 'L') { while (*q != ';') q++; q++; } else q++; }
                                                    else q++;
                                                    idx2++;
                                                }
                                                t = *q;
                                            }
                                        }
                                        void *av = args[argi];
                                        if (t == 'Z') {
                                            snprintf(tmp, sizeof(tmp), "%s", ((int)(intptr_t)av) ? "true" : "false");
                                        } else if (t == 'C') {
                                            snprintf(tmp, sizeof(tmp), "%c", (char)(intptr_t)av);
                                        } else if (t == 'I' || t == 'B' || t == 'S') {
                                            snprintf(tmp, sizeof(tmp), "%d", (int)(intptr_t)av);
                                        } else if (t == 'J') {
                                            snprintf(tmp, sizeof(tmp), "%ld", (long)(intptr_t)av);
                                        } else if (t == 'F') {
                                            int bits = (int)(intptr_t)av;
                                            float fv; memcpy(&fv, &bits, sizeof(fv));
                                            snprintf(tmp, sizeof(tmp), "%g", fv);
                                        } else if (t == 'D') {
                                            long bits = (long)(intptr_t)av;
                                            double dv; memcpy(&dv, &bits, sizeof(dv));
                                            snprintf(tmp, sizeof(tmp), "%g", dv);
                                        } else if ((t == 'L' || t == '[') && nabu_box_kind(av)) {
                                            char bb[8];
                                            snprintf(tmp, sizeof(tmp), "%s", nabu_box_text(av, bb));
                                        } else if (t == 'L' || t == '[') {
                                            char *a = (char *)av;
                                            int printable = (av != NULL) && ((uintptr_t)av >= (1 << 20));
                                            if (printable) {
                                                for (int k = 0; k < 8 && a[k]; k++) if ((unsigned char)a[k] < 32) { printable = 0; break; }
                                            }
                                            if (av == NULL) snprintf(tmp, sizeof(tmp), "null");
                                            else if (printable && a && a[0]) snprintf(tmp, sizeof(tmp), "%s", a);
                                            else snprintf(tmp, sizeof(tmp), "%d", (int)(intptr_t)av);
                                        } else {
                                            snprintf(tmp, sizeof(tmp), "%d", (int)(intptr_t)av);
                                        }
                                    } else {
                                        snprintf(tmp, sizeof(tmp), "null");
                                    }
                                    size_t sl = strlen(tmp);
                                    while (len + sl + 1 > cap) { cap *= 2; res = (char *)realloc(res, cap); }
                                    memcpy(res + len, tmp, sl); len += sl; res[len] = 0;
                                    argi++; rp++;
                                } else {
                                    while (len + 2 > cap) { cap *= 2; res = (char *)realloc(res, cap); }
                                    res[len++] = *rp; res[len] = 0; rp++;
                                }
                            }
                            free(args);
                            push(f, (void *)res);
                        } else {
                            /* Detect lambda: a static arg contains "MH:" (method handle). */
                            int is_lambda = 0;
                            char *mh_str = NULL;
                            char *mt_str = NULL;
                            if (bm != NULL) {
                                for (int k = 0; k < bm->num_static_args; k++) {
                                    if (bm->static_args[k] != NULL && strstr(bm->static_args[k], "MH:") != NULL) {
                                        is_lambda = 1;
                                        mh_str = bm->static_args[k];
                                    }
                                    if (bm->static_args[k] != NULL && strstr(bm->static_args[k], "MT:") != NULL) {
                                        mt_str = bm->static_args[k];
                                    }
                                }
                            }
                            int pcnt = (mdesc != NULL) ? count_params(mdesc) : 0;
                            if (is_lambda && mh_str != NULL) {
                                /* Pop captured args. */
                                void **captured = (void **)malloc(sizeof(void *) * (pcnt > 0 ? pcnt : 1));
                                for (int i = pcnt - 1; i >= 0; i--) captured[i] = pop(f);
                                /* Parse impl method from "MH:cls/name desc". */
                                char *p = mh_str + 3; /* skip "MH:" */
                                char *slash = strchr(p, '/');
                                char *space = slash ? strchr(slash, ' ') : NULL; /* actually desc follows name without space? format cls/name desc? we stored "MH:cls/name desc"? Let's parse: stored as "MH:%s/%s%s" => MH:cls/name desc (no space between name and desc). Actually format "MH:%s/%s%s" => cls/name + desc (desc starts with '('). */
                                char impl_class[256], impl_name[128], impl_desc[256];
                                memset(impl_class, 0, sizeof(impl_class));
                                memset(impl_name, 0, sizeof(impl_name));
                                memset(impl_desc, 0, sizeof(impl_desc));
                                /* p points after "MH:". Find the '(' of the descriptor. */
                                char *paren = strchr(p, '(');
                                if (paren != NULL) {
                                    /* last '/' before paren separates class and method name */
                                    char *lastslash = NULL;
                                    for (char *q = p; q < paren; q++) if (*q == '/') lastslash = q;
                                    if (lastslash != NULL) {
                                        size_t clen = lastslash - p;
                                        if (clen >= sizeof(impl_class)) clen = sizeof(impl_class)-1;
                                        memcpy(impl_class, p, clen);
                                        impl_class[clen] = 0;
                                        size_t nlen = paren - (lastslash + 1);
                                        if (nlen >= sizeof(impl_name)) nlen = sizeof(impl_name)-1;
                                        memcpy(impl_name, lastslash + 1, nlen);
                                        impl_name[nlen] = 0;
                                    } else {
                                        /* no slash: whole thing is method name? unlikely */
                                        strncpy(impl_name, p, sizeof(impl_name)-1);
                                    }
                                    strncpy(impl_desc, paren, sizeof(impl_desc)-1);
                                } else {
                                    /* no descriptor; treat whole as class/name */
                                    char *ls = strrchr(p, '/');
                                    if (ls != NULL) {
                                        size_t clen = ls - p;
                                        if (clen >= sizeof(impl_class)) clen = sizeof(impl_class)-1;
                                        memcpy(impl_class, p, clen);
                                        impl_class[clen] = 0;
                                        strncpy(impl_name, ls + 1, sizeof(impl_name)-1);
                                    } else {
                                        strncpy(impl_name, p, sizeof(impl_name)-1);
                                    }
                                }
                                /* sam name from invokedynamic name */
                                /* mname may be null? use the id name? We have mname? In invokedynamic, mname computed. Use a fallback. */
                                /* captured_count = pcnt */
                                nabu_closure_t *cl = (nabu_closure_t *)calloc(1, sizeof(nabu_closure_t));
                                cl->impl_class = strdup(impl_class[0] ? impl_class : "?");
                                cl->impl_name = strdup(impl_name[0] ? impl_name : "?");
                                cl->impl_desc = strdup(impl_desc[0] ? impl_desc : (mdesc ? mdesc : "?"));
                                cl->captured_count = pcnt;
                                cl->captured = captured;
                                cl->sam_name = strdup(mname != NULL ? mname : "apply");
                                /* register */
                                if (nabu_closure_count < NABU_MAX_CLOSURES) {
                                    if (nabu_closures == NULL) nabu_closures = (nabu_closure_t **)calloc(NABU_MAX_CLOSURES, sizeof(nabu_closure_t *));
                                    nabu_closures[nabu_closure_count++] = cl;
                                }
                                push(f, (void *)cl);
                            } else {
                                /* Unknown invokedynamic: pop args, push null. */
                                for (int i = 0; i < pcnt; i++) pop(f);
                                push(f, NULL);
                            }
                        }
                    }
                }
                break;
            }
            case 0xbb: { /* new */
                int idx = (m->code[f->pc] << 8) | m->code[f->pc+1]; f->pc += 2;
                char *cname = NULL;
                if (m->const_pool != NULL && idx > 0 && idx < m->const_pool->count) {
                    nabu_cp_entry *e = &m->const_pool->entries[idx];
                    if (e->tag == 7) cname = nabu_cp_class_name(m->const_pool, idx);
                }
                /* Auto-load class before allocating (so field count is correct). */
                if (cname != NULL) nabu_classfile_load_class(cname);
                int fc = cname ? nabu_interp_field_count(cname) : 0;

                fflush(stderr);
                void *obj = calloc(1, sizeof(nabu_object) + (size_t)(fc > 0 ? fc : 1) * sizeof(void *));

                fflush(stderr);
                if (obj != NULL && cname != NULL) {
    
                    fflush(stderr);
                    nabu_type_info *ti = (nabu_type_info *)nabu_register_interpreted_type(cname, "java/lang/Object", 0, NULL, NULL);

                    fflush(stderr);
                    ((nabu_object *)obj)->type = ti;
                    /* Set interfaces from registry. */
                    int ic = nabu_get_interface_count(cname);
                    if (ic > 0 && ti != NULL) {
                        char **ifaces = nabu_get_interfaces(cname);
                        /* Convert to nabu_type_info** ? For now, store as count only (names not in type_info). */
                        ti->interface_count = ic;
                    }
                }
                push(f, obj);
                break;
            }
            case 0xbc: {
                int type = m->code[f->pc++];
                int count = (int)(intptr_t)pop(f);
                int esize = 1;
                switch (type) { case 4: case 8: esize=1; break; case 5: case 9: esize=2; break; case 6: case 10: esize=4; break; case 7: case 11: esize=8; break; default: esize=1; break; }
                void *arr = calloc(1, 8 + (size_t)count * esize);
                if (arr != NULL) *(int *)arr = count;
                push(f, arr);
                break;
            }
            case 0xbd: { f->pc += 2; int count=(int)(intptr_t)pop(f); void *arr=calloc(1,8+(size_t)count*sizeof(void*)); if(arr!=NULL)*(int*)arr=count; push(f,arr); break; }
            case 0xbe: { void *arr=pop(f); int len=0; if (arr == NULL && strict_nulls(m)) { interp_throw_array(1); if (interp_jmp_active) longjmp(interp_jmp_buf, 1); running = 0; break; } if(arr!=NULL) len=*(int*)arr; push(f,(void*)(intptr_t)len); break; }
            case 0xbf: {
                void *exc = pop(f);
                interp_pending_exception = exc;
                if (interp_jmp_active) longjmp(interp_jmp_buf, 1);
                running = 0; break;
            }
            case 0xc0: { f->pc += 2; break; }
            case 0xc5: { /* multianewarray */
                int idx = (m->code[f->pc] << 8) | m->code[f->pc+1]; f->pc += 2;
                int dims = m->code[f->pc++];
                char *cname = NULL;
                if (m->const_pool != NULL && idx > 0 && idx < m->const_pool->count) {
                    nabu_cp_entry *e = &m->const_pool->entries[idx];
                    if (e->tag == 7) cname = nabu_cp_class_name(m->const_pool, idx);
                }
                if (cname == NULL) cname = strdup("[[I");
                int *counts = (int *)malloc(sizeof(int) * (dims > 0 ? dims : 1));
                for (int d = dims - 1; d >= 0; d--) counts[d] = (int)(intptr_t)pop(f);
                void *arr = alloc_multi(cname, counts, dims);
                free(counts);
                push(f, arr);
                break;
            }
            default: {
                fprintf(stderr, "interp: unsupported opcode 0x%02x at pc=%d\n", op, f->pc-1);
                abort();
            }
        }
    }
    interp_jmp_active = prev_active;
    if (prev_active) memcpy(interp_jmp_buf, prev_jmp, sizeof(jmp_buf));
    frame_free(f);
    return result;
}

void *nabu_interp_invoke_by_name(const char *class_name, const char *name,
                                 const char *desc, void **args, int nargs) {
    nabu_interp_method_t *m = nabu_interp_find_method(class_name, name, desc);
    if (m == NULL) {
        fprintf(stderr, "interp: method not found %s.%s%s\n",
                class_name ? class_name : "?", name ? name : "?", desc ? desc : "");
        return NULL;
    }
    return nabu_interp_invoke(m, args, nargs);
}
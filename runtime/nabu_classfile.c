#include "nabu_interpreter.h"
#include "nabu_runtime.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/time.h>
#include <math.h>

/* ---- Constant pool entry (defined in nabu_interpreter.h) ---- */

static unsigned int rd_u2(const unsigned char *p) { return (p[0] << 8) | p[1]; }
static unsigned int rd_u4(const unsigned char *p) { return (p[0]<<24)|(p[1]<<16)|(p[2]<<8)|p[3]; }

char *nabu_cp_utf8(nabu_cp *cp, int idx) {
    if (idx <= 0 || idx >= cp->count) return NULL;
    if (cp->entries[idx].tag != 1) return NULL;
    return cp->entries[idx].utf8;
}

char *nabu_cp_class_name(nabu_cp *cp, int idx) {
    if (idx <= 0 || idx >= cp->count) return NULL;
    if (cp->entries[idx].tag != 7) return NULL;
    return nabu_cp_utf8(cp, cp->entries[idx].name_index);
}

static void cp_free(nabu_cp *cp) {
    if (cp == NULL) return;
    for (int i = 0; i < cp->count; i++) {
        free(cp->entries[i].utf8);
    }
    free(cp->entries);
    free(cp);
}

/* ---- Native method support ---- */

/* Native function signature: void fn(frame, args[], nargs). Sets frame->result/has_result. */
typedef void (*nabu_native_fn)(nabu_interp_frame_t *frame, void **args, int nargs);

typedef struct {
    char *class_name;
    char *name;
    char *desc;
    nabu_native_fn fn;
} nabu_native_entry;

static nabu_native_entry *natives = NULL;
static int native_count = 0;
static int native_cap = 0;

static void register_native(const char *cls, const char *name, const char *desc, nabu_native_fn fn) {
    if (native_count >= native_cap) {
        int nc = native_cap == 0 ? 16 : native_cap * 2;
        nabu_native_entry *a = (nabu_native_entry *)realloc(natives, sizeof(nabu_native_entry) * nc);
        if (a == NULL) return;
        natives = a;
        native_cap = nc;
    }
    natives[native_count].class_name = strdup(cls);
    natives[native_count].name = strdup(name);
    natives[native_count].desc = strdup(desc);
    natives[native_count].fn = fn;
    native_count++;
}

void *nabu_find_native(const char *cls, const char *name, const char *desc) {
    for (int i = 0; i < native_count; i++) {
        if (strcmp(natives[i].class_name, cls) == 0 &&
            strcmp(natives[i].name, name) == 0 &&
            strcmp(natives[i].desc, desc) == 0) {
            return natives[i].fn;
        }
    }
    return NULL;
}

/* Field registry. */
typedef struct {
    char *class_name;
    char *name;
    int index;
} nabu_interp_field_t;

static nabu_interp_field_t *fields = NULL;
static int field_count_g = 0;
static int field_cap = 0;

void nabu_interp_register_field(const char *cls, const char *name, int index) {
    if (field_count_g >= field_cap) {
        int nc = field_cap == 0 ? 16 : field_cap * 2;
        nabu_interp_field_t *a = (nabu_interp_field_t *)realloc(fields, sizeof(nabu_interp_field_t) * nc);
        if (a == NULL) return;
        fields = a;
        field_cap = nc;
    }
    fields[field_count_g].class_name = strdup(cls);
    fields[field_count_g].name = strdup(name);
    fields[field_count_g].index = index;
    field_count_g++;
}

int nabu_interp_field_index(const char *class_name, const char *field_name) {
    for (int i = 0; i < field_count_g; i++) {
        if (strcmp(fields[i].class_name, class_name) == 0 &&
            strcmp(fields[i].name, field_name) == 0) {
            return fields[i].index;
        }
    }
    return -1;
}

int nabu_interp_field_count(const char *class_name) {
    int maxidx = -1;
    for (int i = 0; i < field_count_g; i++) {
        if (strcmp(fields[i].class_name, class_name) == 0) {
            if (fields[i].index > maxidx) maxidx = fields[i].index;
        }
    }
    return maxidx + 1;
}

static void native_println_int(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)frame;
    int idx = (nargs > 1) ? 1 : 0;
    int v = (int)(intptr_t)(args != NULL ? args[idx] : NULL);
    printf("%d\n", v);
    frame->has_result = 0;
}

static void native_println_str(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)frame;
    int idx = (nargs > 1) ? 1 : 0;
    const char *s = (const char *)(args != NULL ? args[idx] : NULL);
    if (s == NULL) printf("null\n");
    else printf("%s\n", s);
    frame->has_result = 0;
}

static void native_println_obj(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)frame;
    int idx = (nargs > 1) ? 1 : 0;
    void *v = args != NULL ? args[idx] : NULL;
    if (v == NULL) { printf("null\n"); frame->has_result = 0; return; }
    /* Heuristic: small values are ints (from unboxed lambda results). */
    if ((uintptr_t)v < (1 << 20)) {
        printf("%d\n", (int)(intptr_t)v);
    } else {
        const char *s = (const char *)v;
        /* check printable */
        int ok = 1;
        for (int i = 0; i < 64 && s[i]; i++) if ((unsigned char)s[i] < 32) { ok = 0; break; }
        if (ok && s[0]) printf("%s\n", s);
        else printf("%p\n", v);
    }
    frame->has_result = 0;
}

static void native_print_int(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)frame;
    int idx = (nargs > 1) ? 1 : 0;
    int v = (int)(intptr_t)(args != NULL ? args[idx] : NULL);
    printf("%d", v);
    frame->has_result = 0;
}

static void native_print_str(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)frame;
    int idx = (nargs > 1) ? 1 : 0;
    const char *s = (const char *)(args != NULL ? args[idx] : NULL);
    if (s == NULL) printf("null");
    else printf("%s", s);
    frame->has_result = 0;
}

static void native_println_char(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)frame;
    int idx = (nargs > 1) ? 1 : 0;
    int v = (int)(intptr_t)(args != NULL ? args[idx] : NULL);
    printf("%c\n", (char)v);
    frame->has_result = 0;
}

static void native_println_long(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)frame;
    int idx = (nargs > 1) ? 1 : 0;
    long v = (long)(intptr_t)(args != NULL ? args[idx] : NULL);
    printf("%ld\n", v);
    frame->has_result = 0;
}

static void native_println_float(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)frame;
    int idx = (nargs > 1) ? 1 : 0;
    int bits = (int)(intptr_t)(args != NULL ? args[idx] : NULL);
    float v; memcpy(&v, &bits, sizeof(v));
    printf("%g\n", v);
    frame->has_result = 0;
}

static void native_println_double(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)frame;
    int idx = (nargs > 1) ? 1 : 0;
    long bits = (long)(intptr_t)(args != NULL ? args[idx] : NULL);
    double v; memcpy(&v, &bits, sizeof(v));
    printf("%g\n", v);
    frame->has_result = 0;
}

static void native_println_byte(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)frame;
    int idx = (nargs > 1) ? 1 : 0;
    int v = (int)(intptr_t)(args != NULL ? args[idx] : NULL);
    printf("%d\n", v);
    frame->has_result = 0;
}

static char *name_to_path(const char *name);

/* Helper: create a real java.lang.Class object with name field set. */
static void *make_class_object(const char *name) {
    if (name == NULL) return NULL;
    nabu_classfile_load("/tmp/opencode/jdkclasses/classes/java/lang/Class.class");
    int fc = nabu_interp_field_count("java/lang/Class");
    void *obj = calloc(1, sizeof(nabu_object) + (size_t)(fc > 0 ? fc : 1) * sizeof(void *));
    if (obj == NULL) return NULL;
    nabu_type_info *ti = (nabu_type_info *)nabu_register_interpreted_type("java/lang/Class", "java/lang/Object", 0, NULL, NULL);
    ((nabu_object *)obj)->type = ti;
    int fi_name = nabu_interp_field_index("java/lang/Class", "name");
    if (fi_name >= 0) {
        void **fields = (void **)((char *)obj + sizeof(nabu_object));
        fields[fi_name] = (void *)strdup(name);
    }
    return obj;
}

/* Helper: extract class name from a Class handle (real object or char*). */
static const char *class_handle_name(void *obj) {
    if (obj == NULL) return NULL;
    const char *tn = nabu_compiled_type_name(obj);
    if (tn && strcmp(tn, "java/lang/Class") == 0) {
        int fi = nabu_interp_field_index("java/lang/Class", "name");
        if (fi >= 0) {
            void **fields = (void **)((char *)obj + sizeof(nabu_object));
            const char *name = (const char *)fields[fi];
            if (name) return name;
        }
    }
    return (const char *)obj;
}

static void native_class_forName0(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    const char *name = (const char *)(args != NULL ? args[0] : NULL);
    if (name == NULL) { frame->result = NULL; frame->has_result = 1; return; }
    frame->result = make_class_object(name);
    frame->has_result = 1;
}

static void native_class_getName(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    const char *handle = class_handle_name(args != NULL ? args[0] : NULL);
    if (handle == NULL) { frame->result = NULL; frame->has_result = 1; return; }
    char *r = strdup(handle);
    for (char *p = r; *p; p++) if (*p == '/') *p = '.';
    frame->result = (void *)r;
    frame->has_result = 1;
}

static void native_class_toString(nabu_interp_frame_t *frame, void **args, int nargs) {
    native_class_getName(frame, args, nargs);
}

static void native_class_getSimpleName(nabu_interp_frame_t *frame, void **args, int nargs) {
    native_class_getName(frame, args, nargs);
    /* strip package */
    if (frame->result != NULL) {
        char *s = (char *)frame->result;
        char *dot = strrchr(s, '.');
        if (dot) {
            char *base = strdup(dot + 1);
            free(s);
            frame->result = base;
        }
    }
}

static void native_object_getClass(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    void *obj = (args != NULL) ? args[0] : NULL;
    const char *tn = obj ? nabu_compiled_type_name(obj) : NULL;
    if (tn == NULL) tn = "java/lang/Object";
    frame->result = make_class_object(tn);
    frame->has_result = 1;
}

static void native_integer_toHexString(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    int v = (args != NULL) ? (int)(intptr_t)args[0] : 0;
    char buf[64];
    snprintf(buf, sizeof(buf), "%x", (unsigned int)v);
    frame->result = (void *)strdup(buf);
    frame->has_result = 1;
}

static void native_class_getName0(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    const char *handle = class_handle_name(args != NULL ? args[0] : NULL);
    if (handle == NULL) { frame->result = NULL; frame->has_result = 1; return; }
    char *r = strdup(handle);
    for (char *p = r; *p; p++) if (*p == '/') *p = '.';
    frame->result = (void *)r;
    frame->has_result = 1;
}

static void native_reflection_getCallerClass(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)args; (void)nargs;
    /* Return a handle for java.lang.Object as caller (no security). */
    frame->result = (void *)strdup("java.lang.Object");
    frame->has_result = 1;
}

static void native_null_return(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)args; (void)nargs;
    frame->result = NULL;
    frame->has_result = 1;
}

static void native_identity(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    frame->result = (args != NULL && nargs > 0) ? args[0] : NULL;
    frame->has_result = 1;
}

static void native_zero_long(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)args; (void)nargs;
    frame->result = (void *)(intptr_t)0;
    frame->has_result = 1;
}

/* ---- Method side table (for Method.invoke) ---- */
typedef struct { void *obj; char *cls; char *name; char *desc; } nabu_method_info_t;
#define NABU_MAX_METHODS 4096
static nabu_method_info_t nabu_method_table[NABU_MAX_METHODS];
static int nabu_method_table_count = 0;

void nabu_register_method_info(void *obj, const char *cls, const char *name, const char *desc) {
    if (nabu_method_table_count < NABU_MAX_METHODS) {
        nabu_method_table[nabu_method_table_count].obj = obj;
        nabu_method_table[nabu_method_table_count].cls = strdup(cls);
        nabu_method_table[nabu_method_table_count].name = strdup(name);
        nabu_method_table[nabu_method_table_count].desc = strdup(desc);
        nabu_method_table_count++;
    }
}

/* ---- Constructor side table ---- */
typedef struct { void *obj; char *cls; char *desc; } nabu_constructor_info_t;
#define NABU_MAX_CTORS 1024
static nabu_constructor_info_t nabu_ctor_table[NABU_MAX_CTORS];
static int nabu_ctor_table_count = 0;

static void nabu_register_ctor_info(void *obj, const char *cls, const char *desc) {
    if (nabu_ctor_table_count < NABU_MAX_CTORS) {
        nabu_ctor_table[nabu_ctor_table_count].obj = obj;
        nabu_ctor_table[nabu_ctor_table_count].cls = strdup(cls);
        nabu_ctor_table[nabu_ctor_table_count].desc = strdup(desc);
        nabu_ctor_table_count++;
    }
}

/* ---- Field side table ---- */
typedef struct { void *obj; char *cls; char *name; int index; } nabu_field_info_t;
#define NABU_MAX_FIELDS 4096
static nabu_field_info_t nabu_field_table[NABU_MAX_FIELDS];
static int nabu_field_table_count = 0;

static void nabu_register_field_info(void *obj, const char *cls, const char *name, int index) {
    if (nabu_field_table_count < NABU_MAX_FIELDS) {
        nabu_field_table[nabu_field_table_count].obj = obj;
        nabu_field_table[nabu_field_table_count].cls = strdup(cls);
        nabu_field_table[nabu_field_table_count].name = strdup(name);
        nabu_field_table[nabu_field_table_count].index = index;
        nabu_field_table_count++;
    }
}

static nabu_field_info_t *nabu_lookup_field_info(void *obj) {
    for (int i = 0; i < nabu_field_table_count; i++) {
        if (nabu_field_table[i].obj == obj) return &nabu_field_table[i];
    }
    return NULL;
}

static nabu_constructor_info_t *nabu_lookup_ctor_info(void *obj) {
    for (int i = 0; i < nabu_ctor_table_count; i++) {
        if (nabu_ctor_table[i].obj == obj) return &nabu_ctor_table[i];
    }
    return NULL;
}

static nabu_method_info_t *nabu_lookup_method_info(void *obj) {
    for (int i = 0; i < nabu_method_table_count; i++) {
        if (nabu_method_table[i].obj == obj) return &nabu_method_table[i];
    }
    return NULL;
}

static void native_class_getDeclaredMethods(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    const char *clsname = class_handle_name(args != NULL ? args[0] : NULL);
    if (clsname == NULL) { frame->result = NULL; frame->has_result = 1; return; }
    char internal[256];
    snprintf(internal, sizeof(internal), "%s", clsname);
    for (char *p = internal; *p; p++) if (*p == '.') *p = '/';
    /* ensure Method.class loaded for field indices */
    nabu_classfile_load("/tmp/opencode/jdkclasses/classes/java/lang/reflect/Method.class");
    /* collect methods */
    /* count */
    int cnt = nabu_interp_methods_for_class(internal, NULL, 10000);
    /* allocate array: 8 + cnt * sizeof(void*) */
    void *arr = calloc(1, 8 + (size_t)(cnt > 0 ? cnt : 1) * sizeof(void *));
    if (arr != NULL) *(int *)arr = cnt;
    void **elems = (void **)((char *)arr + 8);
    int i = 0;
    /* field indices */
    int fi_clazz = nabu_interp_field_index("java/lang/reflect/Method", "clazz");
    int fi_name = nabu_interp_field_index("java/lang/reflect/Method", "name");
    int fi_modifiers = nabu_interp_field_index("java/lang/reflect/Method", "modifiers");
    int fi_slot = nabu_interp_field_index("java/lang/reflect/Method", "slot");
    int fc = nabu_interp_field_count("java/lang/reflect/Method");
    /* collect */
    nabu_interp_method_t **ms = (nabu_interp_method_t **)malloc(sizeof(nabu_interp_method_t *) * (cnt > 0 ? cnt : 1));
    nabu_interp_methods_for_class(internal, ms, cnt > 0 ? cnt : 1);
    for (int mi = 0; mi < cnt && i < cnt; mi++) {
        nabu_interp_method_t *m = ms[mi];
        if (m == NULL) continue;
        /* allocate Method object */
        void *obj = calloc(1, sizeof(nabu_object) + (size_t)(fc > 0 ? fc : 1) * sizeof(void *));
        if (obj == NULL) continue;
        nabu_type_info *ti = (nabu_type_info *)nabu_register_interpreted_type("java/lang/reflect/Method", "java/lang/Object", 0, NULL, NULL);
        ((nabu_object *)obj)->type = ti;
        void **fields = (void **)((char *)obj + sizeof(nabu_object));
        if (fi_clazz >= 0) fields[fi_clazz] = make_class_object(clsname); /* Class object */
        if (fi_name >= 0) fields[fi_name] = (void *)strdup(m->name);
        if (fi_modifiers >= 0) fields[fi_modifiers] = (void *)(intptr_t)1; /* public */
        if (fi_slot >= 0) fields[fi_slot] = (void *)(intptr_t)0;
        nabu_register_method_info(obj, m->class_name, m->name, m->desc);
        elems[i++] = obj;
    }
    frame->result = arr;
    frame->has_result = 1;
}

static void native_method_invoke(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    void *methodObj = (args != NULL) ? args[0] : NULL;
    void *target = (nargs > 1) ? args[1] : NULL;
    /* args[2] is Object[] */
    void **argArray = (nargs > 2 && args[2] != NULL) ? (void **)((char *)args[2] + 8) : NULL;
    int argc = (nargs > 2 && args[2] != NULL) ? *(int *)args[2] : 0;
    nabu_method_info_t *info = nabu_lookup_method_info(methodObj);
    if (info == NULL) {
        fprintf(stderr, "method.invoke: unknown method object %p\n", methodObj);
        frame->result = NULL; frame->has_result = 1; return;
    }

    /* count params from desc */
    int pcnt = 0;
    const char *p = strchr(info->desc, '(');
    if (p) {
        p++;
        while (*p != ')') {
            if (*p == 'L') { while (*p != ';') p++; p++; }
            else if (*p == '[') { while (*p == '[') p++; if (*p == 'L') { while (*p != ';') p++; p++; } else p++; }
            else p++;
            pcnt++;
        }
    }
    /* build args: if instance method (not static), prepend target */
    /* We don't know static vs instance; assume instance if target != NULL and method not static. Use target always as first arg if target != NULL and pcnt matches. */
    /* Determine if desc has 'this' already? desc is the method desc (params only, no this). For instance, add 'this'. */
    /* Heuristic: if target != NULL, treat as instance method: total = pcnt + 1 */
    int isInstance = (target != NULL);
    int total = isInstance ? pcnt + 1 : pcnt;
    void **cargs = (void **)malloc(sizeof(void *) * (total > 0 ? total : 1));
    int idx = 0;
    if (isInstance) cargs[idx++] = target;
    for (int i = 0; i < pcnt && i < argc; i++) {
        cargs[idx++] = argArray ? argArray[i] : NULL;
    }
    /* for remaining params not in array, pass 0/null */
    while (idx < total) cargs[idx++] = NULL;
    /* find method */
    nabu_interp_method_t *m = nabu_interp_find_method(info->cls, info->name, info->desc);
    void *r = NULL;
    if (m != NULL) {
        r = nabu_interp_invoke(m, cargs, total);
    } else {
        void *nf = nabu_find_native(info->cls, info->name, info->desc);
        if (nf != NULL) {
            nabu_interp_method_t *nm = (nabu_interp_method_t *)calloc(1, sizeof(nabu_interp_method_t));
            nm->class_name = strdup(info->cls);
            nm->name = strdup(info->name);
            nm->desc = strdup(info->desc);
            nm->is_native = 1;
            nm->native_fn = nf;
            nm->max_locals = 8; nm->max_stack = 8;
            r = nabu_interp_invoke(nm, cargs, total);
        } else {
            fprintf(stderr, "method.invoke: not found %s.%s%s\n", info->cls, info->name, info->desc);
        }
    }
    free(cargs);
    frame->result = r;
    frame->has_result = 1;
}

static void native_class_getDeclaredConstructors(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    const char *clsname = class_handle_name(args != NULL ? args[0] : NULL);
    if (clsname == NULL) { frame->result = NULL; frame->has_result = 1; return; }
    char internal[256];
    snprintf(internal, sizeof(internal), "%s", clsname);
    for (char *p = internal; *p; p++) if (*p == '.') *p = '/';
    nabu_classfile_load("/tmp/opencode/jdkclasses/classes/java/lang/reflect/Constructor.class");
    /* collect <init> methods */
    int cnt = 0;
    nabu_interp_method_t **all = NULL;
    int total_methods = nabu_interp_methods_for_class(internal, NULL, 10000);
    if (total_methods > 0) {
        all = (nabu_interp_method_t **)malloc(sizeof(nabu_interp_method_t *) * total_methods);
        nabu_interp_methods_for_class(internal, all, total_methods);
    }
    for (int i = 0; i < total_methods; i++) {
        if (all[i] && all[i]->name && strcmp(all[i]->name, "<init>") == 0) cnt++;
    }
    void *arr = calloc(1, 8 + (size_t)(cnt > 0 ? cnt : 1) * sizeof(void *));
    if (arr != NULL) *(int *)arr = cnt;
    void **elems = (void **)((char *)arr + 8);
    int fi_clazz = nabu_interp_field_index("java/lang/reflect/Constructor", "clazz");
    int fc = nabu_interp_field_count("java/lang/reflect/Constructor");
    int idx = 0;
    for (int i = 0; i < total_methods && idx < cnt; i++) {
        nabu_interp_method_t *m = all[i];
        if (m == NULL || m->name == NULL || strcmp(m->name, "<init>") != 0) continue;
        void *obj = calloc(1, sizeof(nabu_object) + (size_t)(fc > 0 ? fc : 1) * sizeof(void *));
        if (obj == NULL) continue;
        nabu_type_info *ti = (nabu_type_info *)nabu_register_interpreted_type("java/lang/reflect/Constructor", "java/lang/Object", 0, NULL, NULL);
        ((nabu_object *)obj)->type = ti;
        void **fields = (void **)((char *)obj + sizeof(nabu_object));
        if (fi_clazz >= 0) fields[fi_clazz] = make_class_object(clsname);
        nabu_register_ctor_info(obj, m->class_name, m->desc);
        elems[idx++] = obj;
    }
    if (all) free(all);
    frame->result = arr;
    frame->has_result = 1;
}

static void native_constructor_newInstance(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    void *ctorObj = (args != NULL) ? args[0] : NULL;
    void **argArray = (nargs > 1 && args[1] != NULL) ? (void **)((char *)args[1] + 8) : NULL;
    int argc = (nargs > 1 && args[1] != NULL) ? *(int *)args[1] : 0;
    nabu_constructor_info_t *info = nabu_lookup_ctor_info(ctorObj);
    if (info == NULL) {
        fprintf(stderr, "constructor.newInstance: unknown ctor %p\n", ctorObj);
        frame->result = NULL; frame->has_result = 1; return;
    }
    char internal[256];
    snprintf(internal, sizeof(internal), "%s", info->cls);
    for (char *p = internal; *p; p++) if (*p == '.') *p = '/';
    /* create instance */
    int fc = nabu_interp_field_count(internal);
    void *obj = calloc(1, sizeof(nabu_object) + (size_t)(fc > 0 ? fc : 1) * sizeof(void *));
    if (obj != NULL) {
        nabu_type_info *ti = (nabu_type_info *)nabu_register_interpreted_type(internal, "java/lang/Object", 0, NULL, NULL);
        ((nabu_object *)obj)->type = ti;
        /* invoke <init> */
        nabu_interp_method_t *m = nabu_interp_find_method(internal, "<init>", info->desc);
        if (m != NULL) {
            void **cargs = (void **)malloc(sizeof(void *) * (argc + 1));
            cargs[0] = obj;
            for (int i = 0; i < argc; i++) cargs[i + 1] = argArray ? argArray[i] : NULL;
            nabu_interp_invoke(m, cargs, argc + 1);
            free(cargs);
        }
    }
    frame->result = obj;
    frame->has_result = 1;
}

/* ---- StringBuilder buffer side table ---- */
#define NABU_MAX_SB 1024
static void *sb_objs[NABU_MAX_SB];
static char *sb_bufs[NABU_MAX_SB];
static int sb_count = 0;

static char *sb_get(void *obj) {
    for (int i = 0; i < sb_count; i++) if (sb_objs[i] == obj) return sb_bufs[i];
    return NULL;
}
static void sb_set(void *obj, const char *s) {
    for (int i = 0; i < sb_count; i++) {
        if (sb_objs[i] == obj) { free(sb_bufs[i]); sb_bufs[i] = strdup(s ? s : ""); return; }
    }
    if (sb_count < NABU_MAX_SB) {
        sb_objs[sb_count] = obj;
        sb_bufs[sb_count] = strdup(s ? s : "");
        sb_count++;
    }
}

static void native_sb_init(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    void *obj = (args != NULL) ? args[0] : NULL;
    if (obj) sb_set(obj, "");
    frame->has_result = 0;
}

static void native_sb_init_str(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    void *obj = (args != NULL) ? args[0] : NULL;
    const char *s = (nargs > 1 && args[1]) ? (const char *)args[1] : "";
    if (obj) sb_set(obj, s);
    frame->has_result = 0;
}

static void native_sb_append_str(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    void *obj = (args != NULL) ? args[0] : NULL;
    const char *s = (nargs > 1 && args[1]) ? (const char *)args[1] : "";
    char *cur = sb_get(obj);
    if (cur == NULL) { sb_set(obj, s); cur = sb_get(obj); }
    else {
        size_t len = strlen(cur) + strlen(s) + 1;
        char *nb = (char *)malloc(len);
        snprintf(nb, len, "%s%s", cur, s);
        sb_set(obj, nb);
        free(nb);
    }
    frame->result = obj;
    frame->has_result = 1;
}

static void native_sb_append_int(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    void *obj = (args != NULL) ? args[0] : NULL;
    int v = (nargs > 1) ? (int)(intptr_t)args[1] : 0;
    char tmp[64]; snprintf(tmp, sizeof(tmp), "%d", v);
    /* reuse append_str logic */
    void *a[2] = { obj, (void *)strdup(tmp) };
    native_sb_append_str(frame, a, 2);
}

static void native_sb_toString(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    void *obj = (args != NULL) ? args[0] : NULL;
    char *cur = sb_get(obj);
    frame->result = (void *)strdup(cur ? cur : "");
    frame->has_result = 1;
}

static void native_object_toString(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    void *obj = (args != NULL) ? args[0] : NULL;
    if (obj == NULL) { frame->result = (void *)strdup("null"); frame->has_result = 1; return; }
    /* If it's a small integer, return its string representation. */
    if ((uintptr_t)obj < (1 << 20)) {
        char buf[64];
        snprintf(buf, sizeof(buf), "%d", (int)(intptr_t)obj);
        frame->result = (void *)strdup(buf);
        frame->has_result = 1;
        return;
    }
    /* If it's a string (char*), return it. */
    const char *s = (const char *)obj;
    int printable = 1;
    for (int i = 0; i < 8 && s[i]; i++) if ((unsigned char)s[i] < 32) { printable = 0; break; }
    if (printable && s[0]) { frame->result = (void *)strdup(s); frame->has_result = 1; return; }
    char *cn = (char *)nabu_compiled_type_name(obj);
    char buf[256];
    snprintf(buf, sizeof(buf), "%s@%p", cn ? cn : "Object", obj);
    frame->result = (void *)strdup(buf);
    frame->has_result = 1;
}

static void native_string_valueOf_obj(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    void *v = (args != NULL) ? args[0] : NULL;
    if (v == NULL) { frame->result = (void *)strdup("null"); frame->has_result = 1; return; }
    /* if it's a char* string already */
    int printable = 0;
    if ((uintptr_t)v >= (1 << 20)) {
        const char *s = (const char *)v;
        printable = 1;
        for (int i = 0; i < 8 && s[i]; i++) if ((unsigned char)s[i] < 32) { printable = 0; break; }
        if (printable && s[0]) { frame->result = (void *)strdup(s); frame->has_result = 1; return; }
    }
    /* otherwise treat as int */
    char buf[64]; snprintf(buf, sizeof(buf), "%d", (int)(intptr_t)v);
    frame->result = (void *)strdup(buf);
    frame->has_result = 1;
}

static void native_integer_toString(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    int v = (args != NULL) ? (int)(intptr_t)args[0] : 0;
    /* args[0] might be the int itself (static method) */
    char buf[64]; snprintf(buf, sizeof(buf), "%d", v);
    frame->result = (void *)strdup(buf);
    frame->has_result = 1;
}

static void native_identity_str(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    frame->result = (args != NULL) ? args[0] : NULL;
    frame->has_result = 1;
}

static void native_identity_int(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    frame->result = (args != NULL) ? args[0] : NULL;
    frame->has_result = 1;
}

static void native_reflect_loadServices(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    const char *iface = (const char *)(args != NULL ? args[0] : NULL);
    /* Collect matching impls from /tmp/opencode/services.txt */
    /* First pass: count */
    int cnt = 0;
    char impls[64][256];
    memset(impls, 0, sizeof(impls));

    if (iface) {
        FILE *f = fopen("/tmp/opencode/services.txt", "r");
        if (f) {
            char line[512];
            while (fgets(line, sizeof(line), f)) {
                /* trim newline */
                char *nl = strchr(line, '\n'); if (nl) *nl = 0;
                /* format: iface=impl */
                char *eq = strchr(line, '=');
                if (!eq) continue;
                *eq = 0;
                const char *k = line;
                const char *v = eq + 1;
                if (strcmp(k, iface) == 0 && cnt < 64) {
                    snprintf(impls[cnt], sizeof(impls[cnt]), "%s", v);
                    cnt++;
                }
            }
            fclose(f);
        }
    }
    void *arr = calloc(1, 8 + (size_t)(cnt > 0 ? cnt : 1) * sizeof(void *));
    if (arr != NULL) *(int *)arr = cnt;
    void **elems = (void **)((char *)arr + 8);
    for (int i = 0; i < cnt; i++) {
        /* load class */
        char internal[256];
        snprintf(internal, sizeof(internal), "%s", impls[i]);
        for (char *p = internal; *p; p++) if (*p == '.') *p = '/';
        /* ensure class loaded */
        char *path = name_to_path(impls[i]);
        nabu_classfile_load(path);
        free(path);
        /* create instance via default ctor */
        int fc = nabu_interp_field_count(internal);
        void *obj = calloc(1, sizeof(nabu_object) + (size_t)(fc > 0 ? fc : 1) * sizeof(void *));
        if (obj != NULL) {
            nabu_type_info *ti = (nabu_type_info *)nabu_register_interpreted_type(internal, "java/lang/Object", 0, NULL, NULL);
            ((nabu_object *)obj)->type = ti;
            nabu_interp_method_t *m = nabu_interp_find_method(internal, "<init>", "()V");
            if (m != NULL) {
                void *a[1] = { obj };
                nabu_interp_invoke(m, a, 1);
            }
        }
        elems[i] = obj;
    }
    frame->result = arr;
    frame->has_result = 1;
}

static void native_class_getDeclaredFields(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    const char *clsname = class_handle_name(args != NULL ? args[0] : NULL);
    if (clsname == NULL) { frame->result = NULL; frame->has_result = 1; return; }
    char internal[256];
    snprintf(internal, sizeof(internal), "%s", clsname);
    for (char *p = internal; *p; p++) if (*p == '.') *p = '/';
    nabu_classfile_load("/tmp/opencode/jdkclasses/classes/java/lang/reflect/Field.class");
    /* count fields for this class */
    int cnt = 0;
    for (int i = 0; i < field_count_g; i++) {
        if (strcmp(fields[i].class_name, internal) == 0) cnt++;
    }

    void *arr = calloc(1, 8 + (size_t)(cnt > 0 ? cnt : 1) * sizeof(void *));
    if (arr != NULL) *(int *)arr = cnt;
    void **elems = (void **)((char *)arr + 8);
    int fi_clazz = nabu_interp_field_index("java/lang/reflect/Field", "clazz");
    int fi_name = nabu_interp_field_index("java/lang/reflect/Field", "name");
    int fi_modifiers = nabu_interp_field_index("java/lang/reflect/Field", "modifiers");
    int fc = nabu_interp_field_count("java/lang/reflect/Field");
    int idx = 0;
    /* iterate unique field names */
    /* We need to collect unique field names for this class */
    /* Simple: iterate field registry, for each field of this class, create Field object */
    /* But field registry may have duplicates? It shouldn't. */
    for (int i = 0; i < field_count_g && idx < cnt; i++) {
        if (strcmp(fields[i].class_name, internal) != 0) continue;
        /* check duplicate name */
        int dup = 0;
        for (int j = 0; j < i; j++) {
            if (strcmp(fields[j].class_name, internal) == 0 && strcmp(fields[j].name, fields[i].name) == 0) { dup = 1; break; }
        }
        if (dup) continue;
        void *obj = calloc(1, sizeof(nabu_object) + (size_t)(fc > 0 ? fc : 1) * sizeof(void *));
        if (obj == NULL) continue;
        nabu_type_info *ti = (nabu_type_info *)nabu_register_interpreted_type("java/lang/reflect/Field", "java/lang/Object", 0, NULL, NULL);
        ((nabu_object *)obj)->type = ti;
        void **flds = (void **)((char *)obj + sizeof(nabu_object));
        if (fi_clazz >= 0) flds[fi_clazz] = (void *)strdup(clsname);
        if (fi_name >= 0) flds[fi_name] = (void *)strdup(fields[i].name);
        if (fi_modifiers >= 0) flds[fi_modifiers] = (void *)(intptr_t)1;
        nabu_register_field_info(obj, internal, fields[i].name, fields[i].index);
        elems[idx++] = obj;
    }
    /* fix count */
    if (arr != NULL) *(int *)arr = idx;
    frame->result = arr;
    frame->has_result = 1;
}

static void native_field_get(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    void *fieldObj = (args != NULL) ? args[0] : NULL;
    void *target = (nargs > 1) ? args[1] : NULL;
    nabu_field_info_t *info = nabu_lookup_field_info(fieldObj);
    if (info == NULL) {
        fprintf(stderr, "field.get: unknown field %p\n", fieldObj);
        frame->result = NULL; frame->has_result = 1; return;
    }
    void *val = NULL;
    if (target != NULL) {
        void **fields = (void **)((char *)target + sizeof(nabu_object));
        /* need field count to bounds-check; just read index */
        if (info->index >= 0) val = fields[info->index];
    }
    frame->result = val;
    frame->has_result = 1;
}

static void native_field_set(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    void *fieldObj = (args != NULL) ? args[0] : NULL;
    void *target = (nargs > 1) ? args[1] : NULL;
    void *value = (nargs > 2) ? args[2] : NULL;
    nabu_field_info_t *info = nabu_lookup_field_info(fieldObj);
    if (info == NULL) {
        fprintf(stderr, "field.set: unknown field %p\n", fieldObj);
        frame->has_result = 0; return;
    }
    if (target != NULL && info->index >= 0) {
        void **fields = (void **)((char *)target + sizeof(nabu_object));
        fields[info->index] = value;
    }
    frame->has_result = 0;
}

/* ---- Proxy side table ---- */
#define NABU_MAX_PROXY 256
static void *proxy_objs[NABU_MAX_PROXY];
static void *proxy_handlers[NABU_MAX_PROXY];
static char *proxy_ifaces[NABU_MAX_PROXY];
static int proxy_count = 0;

int nabu_is_proxy(void *p) {
    for (int i = 0; i < proxy_count; i++) if (proxy_objs[i] == p) return 1;
    return 0;
}

void *nabu_proxy_handler(void *p) {
    for (int i = 0; i < proxy_count; i++) if (proxy_objs[i] == p) return proxy_handlers[i];
    return NULL;
}

static int is_proxy(void *p) {
    for (int i = 0; i < proxy_count; i++) if (proxy_objs[i] == p) return 1;
    return 0;
}

static void *proxy_handler(void *p) {
    for (int i = 0; i < proxy_count; i++) if (proxy_objs[i] == p) return proxy_handlers[i];
    return NULL;
}

static char *proxy_iface(void *p) {
    for (int i = 0; i < proxy_count; i++) if (proxy_objs[i] == p) return proxy_ifaces[i];
    return NULL;
}

static void native_reflect_newProxy(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    const char *iface = (const char *)(args != NULL ? args[0] : NULL);
    void *handler = (nargs > 1) ? args[1] : NULL;
    /* create a proxy object: allocate with type info for the iface */
    char internal[256];
    snprintf(internal, sizeof(internal), "%s", iface ? iface : "java/lang/Object");
    for (char *p = internal; *p; p++) if (*p == '.') *p = '/';
    int fc = 0;
    void *obj = calloc(1, sizeof(nabu_object) + (size_t)(fc > 0 ? fc : 1) * sizeof(void *));
    if (obj != NULL) {
        nabu_type_info *ti = (nabu_type_info *)nabu_register_interpreted_type(internal, "java/lang/Object", 0, NULL, NULL);
        ((nabu_object *)obj)->type = ti;
        if (proxy_count < NABU_MAX_PROXY) {
            proxy_objs[proxy_count] = obj;
            proxy_handlers[proxy_count] = handler;
            proxy_ifaces[proxy_count] = strdup(internal);
            proxy_count++;
        }
    }
    frame->result = obj;
    frame->has_result = 1;
}

static void native_reflect_isProxy(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    void *obj = (args != NULL) ? args[0] : NULL;
    frame->result = (void *)(intptr_t)(nabu_is_proxy(obj) ? 1 : 0);
    frame->has_result = 1;
}

static void native_reflect_isProxyClass(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    /* args[0] is a Class handle (char*). We can't easily check; return 0. */
    frame->result = (void *)(intptr_t)0;
    frame->has_result = 1;
}

static void native_reflect_getInvocationHandler(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    void *obj = (args != NULL) ? args[0] : NULL;
    void *h = nabu_proxy_handler(obj);
    frame->result = h;
    frame->has_result = 1;
}

static void native_integer_parseInt(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    const char *s = (args != NULL) ? (const char *)args[0] : NULL;
    int v = s ? atoi(s) : 0;
    frame->result = (void *)(intptr_t)v;
    frame->has_result = 1;
}

static void native_string_indexOf_char(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    const char *s = (args != NULL) ? (const char *)args[0] : NULL;
    int ch = (nargs > 1) ? (int)(intptr_t)args[1] : 0;
    int idx = -1;
    if (s) {
        const char *p = strchr(s, (char)ch);
        if (p) idx = (int)(p - s);
    }
    frame->result = (void *)(intptr_t)idx;
    frame->has_result = 1;
}

static void native_string_indexOf_str(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    const char *s = (args != NULL) ? (const char *)args[0] : NULL;
    const char *sub = (nargs > 1) ? (const char *)args[1] : NULL;
    int idx = -1;
    if (s && sub) {
        const char *p = strstr(s, sub);
        if (p) idx = (int)(p - s);
    }
    frame->result = (void *)(intptr_t)idx;
    frame->has_result = 1;
}

static void native_string_substring(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    const char *s = (args != NULL) ? (const char *)args[0] : NULL;
    int start = (nargs > 1) ? (int)(intptr_t)args[1] : 0;
    int end = (nargs > 2) ? (int)(intptr_t)args[2] : (s ? (int)strlen(s) : 0);
    if (!s) { frame->result = (void *)strdup(""); frame->has_result = 1; return; }
    int len = (int)strlen(s);
    if (start < 0) start = 0;
    if (end > len) end = len;
    if (start > end) start = end;
    char *r = (char *)malloc(end - start + 1);
    memcpy(r, s + start, end - start);
    r[end - start] = 0;
    frame->result = (void *)r;
    frame->has_result = 1;
}

static void native_string_concat(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    const char *a = (args != NULL) ? (const char *)args[0] : NULL;
    const char *b = (nargs > 1) ? (const char *)args[1] : NULL;
    size_t la = a ? strlen(a) : 0;
    size_t lb = b ? strlen(b) : 0;
    char *r = (char *)malloc(la + lb + 1);
    if (a) memcpy(r, a, la);
    if (b) memcpy(r + la, b, lb);
    r[la + lb] = 0;
    frame->result = (void *)r;
    frame->has_result = 1;
}

static void native_string_startsWith(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    const char *s = (args != NULL) ? (const char *)args[0] : NULL;
    const char *prefix = (nargs > 1) ? (const char *)args[1] : NULL;
    int r = 0;
    if (s && prefix) {
        size_t lp = strlen(prefix);
        if (strlen(s) >= lp && strncmp(s, prefix, lp) == 0) r = 1;
    }
    frame->result = (void *)(intptr_t)r;
    frame->has_result = 1;
}

static void native_string_endsWith(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    const char *s = (args != NULL) ? (const char *)args[0] : NULL;
    const char *suffix = (nargs > 1) ? (const char *)args[1] : NULL;
    int r = 0;
    if (s && suffix) {
        size_t ls = strlen(s), lf = strlen(suffix);
        if (ls >= lf && strcmp(s + ls - lf, suffix) == 0) r = 1;
    }
    frame->result = (void *)(intptr_t)r;
    frame->has_result = 1;
}

static void native_math_abs_int(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    int v = (args != NULL) ? (int)(intptr_t)args[0] : 0;
    frame->result = (void *)(intptr_t)(v < 0 ? -v : v);
    frame->has_result = 1;
}

static void native_math_abs_long(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    long v = (args != NULL) ? (long)(intptr_t)args[0] : 0;
    frame->result = (void *)(intptr_t)(v < 0 ? -v : v);
    frame->has_result = 1;
}

static void native_math_min(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    int a = (args != NULL) ? (int)(intptr_t)args[0] : 0;
    int b = (nargs > 1) ? (int)(intptr_t)args[1] : 0;
    frame->result = (void *)(intptr_t)(a < b ? a : b);
    frame->has_result = 1;
}

static void native_math_max(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    int a = (args != NULL) ? (int)(intptr_t)args[0] : 0;
    int b = (nargs > 1) ? (int)(intptr_t)args[1] : 0;
    frame->result = (void *)(intptr_t)(a > b ? a : b);
    frame->has_result = 1;
}

static void native_string_trim(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    const char *s = (args != NULL) ? (const char *)args[0] : NULL;
    if (!s) { frame->result = (void *)strdup(""); frame->has_result = 1; return; }
    const char *start = s;
    while (*start == ' ' || *start == '\t' || *start == '\n' || *start == '\r') start++;
    const char *end = s + strlen(s);
    while (end > start && (end[-1] == ' ' || end[-1] == '\t' || end[-1] == '\n' || end[-1] == '\r')) end--;
    size_t len = end - start;
    char *r = (char *)malloc(len + 1);
    memcpy(r, start, len);
    r[len] = 0;
    frame->result = (void *)r;
    frame->has_result = 1;
}

static void native_string_toUpperCase(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    const char *s = (args != NULL) ? (const char *)args[0] : NULL;
    if (!s) { frame->result = (void *)strdup(""); frame->has_result = 1; return; }
    char *r = strdup(s);
    for (char *p = r; *p; p++) if (*p >= 'a' && *p <= 'z') *p -= 32;
    frame->result = (void *)r;
    frame->has_result = 1;
}

static void native_string_toLowerCase(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    const char *s = (args != NULL) ? (const char *)args[0] : NULL;
    if (!s) { frame->result = (void *)strdup(""); frame->has_result = 1; return; }
    char *r = strdup(s);
    for (char *p = r; *p; p++) if (*p >= 'A' && *p <= 'Z') *p += 32;
    frame->result = (void *)r;
    frame->has_result = 1;
}

static void native_string_replace(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    const char *s = (args != NULL) ? (const char *)args[0] : NULL;
    char oldc = (nargs > 1) ? (char)(int)(intptr_t)args[1] : 0;
    char newc = (nargs > 2) ? (char)(int)(intptr_t)args[2] : 0;
    if (!s) { frame->result = (void *)strdup(""); frame->has_result = 1; return; }
    char *r = strdup(s);
    for (char *p = r; *p; p++) if (*p == oldc) *p = newc;
    frame->result = (void *)r;
    frame->has_result = 1;
}

static void native_system_arraycopy(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)frame; (void)nargs;
    /* args: src, srcPos, dest, destPos, length */
    void *src = (args != NULL) ? args[0] : NULL;
    int srcPos = (nargs > 1) ? (int)(intptr_t)args[1] : 0;
    void *dest = (nargs > 2) ? args[2] : NULL;
    int destPos = (nargs > 3) ? (int)(intptr_t)args[3] : 0;
    int length = (nargs > 4) ? (int)(intptr_t)args[4] : 0;
    if (src && dest) {
        /* both are our arrays: void** with count at offset 0, elements at +8 */
        void **srcElems = (void **)((char *)src + 8);
        void **destElems = (void **)((char *)dest + 8);
        /* element size: assume pointer-sized (reference arrays) or int arrays (4 bytes). 
           Heuristic: if srcPos/src are ints stored as pointers, copy pointer-sized. */
        for (int i = 0; i < length; i++) {
            destElems[destPos + i] = srcElems[srcPos + i];
        }
    }
    frame->has_result = 0;
}

static void native_object_hashCode(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    void *obj = (args != NULL) ? args[0] : NULL;
    if (obj != NULL && (uintptr_t)obj >= (1 << 20)) {
        const char *s = (const char *)obj;
        int printable = 1;
        for (int i = 0; i < 8 && s[i]; i++) if ((unsigned char)s[i] < 32) { printable = 0; break; }
        if (printable && s[0]) {
            int h = 0;
            for (const char *p = s; *p; p++) h = 31 * h + (unsigned char)*p;
            frame->result = (void *)(intptr_t)h;
            frame->has_result = 1;
            return;
        }
    }
    frame->result = (void *)(intptr_t)(obj ? (int)(intptr_t)obj : 0);
    frame->has_result = 1;
}

static void native_object_equals(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    void *a = (args != NULL) ? args[0] : NULL;
    void *b = (nargs > 1) ? args[1] : NULL;
    int r = (a == b) ? 1 : 0;
    if (!r && a && b && (uintptr_t)a >= (1 << 20) && (uintptr_t)b >= (1 << 20)) {
        const char *sa = (const char *)a;
        const char *sb = (const char *)b;
        int pa = 1, pb = 1;
        for (int i = 0; i < 8 && sa[i]; i++) if ((unsigned char)sa[i] < 32) { pa = 0; break; }
        for (int i = 0; i < 8 && sb[i]; i++) if ((unsigned char)sb[i] < 32) { pb = 0; break; }
        if (pa && pb && strcmp(sa, sb) == 0) r = 1;
    }
    frame->result = (void *)(intptr_t)r;
    frame->has_result = 1;
}

static void native_system_currentTimeMillis(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)args; (void)nargs;
    struct timeval tv;
    gettimeofday(&tv, NULL);
    frame->result = (void *)(intptr_t)(tv.tv_sec * 1000LL + tv.tv_usec / 1000);
    frame->has_result = 1;
}

static void native_system_identityHashCode(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    void *obj = (args != NULL) ? args[0] : NULL;
    frame->result = (void *)(intptr_t)(obj ? (int)(intptr_t)obj : 0);
    frame->has_result = 1;
}

static void native_long_toString(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    long v = (args != NULL) ? (long)(intptr_t)args[0] : 0;
    char buf[64];
    snprintf(buf, sizeof(buf), "%ld", v);
    frame->result = (void *)strdup(buf);
    frame->has_result = 1;
}

static void native_long_parseLong(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    const char *s = (args != NULL) ? (const char *)args[0] : NULL;
    long v = s ? atol(s) : 0;
    frame->result = (void *)(intptr_t)v;
    frame->has_result = 1;
}

static void native_double_toString(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    /* args[0] may be double bits */
    long bits = (args != NULL) ? (long)(intptr_t)args[0] : 0;
    double v; memcpy(&v, &bits, sizeof(v));
    char buf[64];
    snprintf(buf, sizeof(buf), "%g", v);
    frame->result = (void *)strdup(buf);
    frame->has_result = 1;
}

static void native_sb_ensureCapacity(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)args; (void)nargs; (void)frame;
    frame->has_result = 0;
}

static void native_sb_capacity(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    void *obj = (args != NULL) ? args[0] : NULL;
    char *cur = sb_get(obj);
    int cap = cur ? (int)strlen(cur) + 16 : 16;
    frame->result = (void *)(intptr_t)cap;
    frame->has_result = 1;
}

static void native_sb_reverse(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    void *obj = (args != NULL) ? args[0] : NULL;
    char *cur = sb_get(obj);
    if (cur) {
        int len = (int)strlen(cur);
        for (int i = 0; i < len / 2; i++) {
            char t = cur[i]; cur[i] = cur[len - 1 - i]; cur[len - 1 - i] = t;
        }
    }
    frame->result = obj;
    frame->has_result = 1;
}

static void native_string_isEmpty(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    const char *s = (args != NULL) ? (const char *)args[0] : NULL;
    int r = (s == NULL || s[0] == 0) ? 1 : 0;
    frame->result = (void *)(intptr_t)r;
    frame->has_result = 1;
}

static void native_thread_currentThread(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)args; (void)nargs;
    frame->result = NULL; /* null = bootstrap context */
    frame->has_result = 1;
}

static void native_bootloader_loadClassOrNull(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    const char *name = (args != NULL) ? (const char *)args[0] : NULL;
    if (name == NULL) { frame->result = NULL; frame->has_result = 1; return; }
    /* Convert dotted to internal */
    char internal[256];
    snprintf(internal, sizeof(internal), "%s", name);
    for (char *p = internal; *p; p++) if (*p == '.') *p = '/';
    /* Try to load */
    if (nabu_classfile_load_class(internal) == 0) {
        frame->result = make_class_object(name);
    } else {
        frame->result = NULL;
    }
    frame->has_result = 1;
}

static void native_bootloader_loadClass(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    /* args: (Module, String) → use args[1] as name */
    const char *name = (nargs > 1 && args[1] != NULL) ? (const char *)args[1] : NULL;
    if (name == NULL && args != NULL && args[0] != NULL) name = (const char *)args[0];
    if (name == NULL) { frame->result = NULL; frame->has_result = 1; return; }
    char internal[256];
    snprintf(internal, sizeof(internal), "%s", name);
    for (char *p = internal; *p; p++) if (*p == '.') *p = '/';
    if (nabu_classfile_load_class(internal) == 0) {
        frame->result = make_class_object(name);
    } else {
        frame->result = NULL;
    }
    frame->has_result = 1;
}

static void native_true(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)args; (void)nargs;
    frame->result = (void *)(intptr_t)1;
    frame->has_result = 1;
}

static void native_classloader_loadClass(nabu_interp_frame_t *frame, void **args, int nargs) {
    const char *name = NULL;
    if (nargs > 1 && args != NULL && args[1] != NULL) name = (const char *)args[1];
    else if (args != NULL && args[0] != NULL) name = (const char *)args[0];
    if (name == NULL) { frame->result = NULL; frame->has_result = 1; return; }
    char internal[256];
    snprintf(internal, sizeof(internal), "%s", name);
    for (char *p = internal; *p; p++) if (*p == '.') *p = '/';
    if (nabu_classfile_load_class(internal) == 0 || nabu_classfile_load_class(name) == 0) {
        frame->result = make_class_object(name);
    } else {
        /* maybe already loaded; return handle anyway */
        frame->result = make_class_object(name);
    }
    frame->has_result = 1;
}

static void native_integer_toString_radix(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    int v = (args != NULL) ? (int)(intptr_t)args[0] : 0;
    int radix = (nargs > 1) ? (int)(intptr_t)args[1] : 10;
    if (radix < 2 || radix > 36) radix = 10;
    char buf[64];
    int i = 0, neg = 0;
    unsigned int uv = (unsigned int)v;
    if (v < 0 && radix == 10) { neg = 1; uv = (unsigned int)(-v); }
    if (uv == 0) buf[i++] = '0';
    while (uv > 0) {
        int d = uv % radix;
        buf[i++] = (d < 10) ? '0' + d : 'a' + d - 10;
        uv /= radix;
    }
    if (neg) buf[i++] = '-';
    buf[i] = 0;
    /* reverse */
    for (int a = 0, b = i - 1; a < b; a++, b--) { char t = buf[a]; buf[a] = buf[b]; buf[b] = t; }
    frame->result = (void *)strdup(buf);
    frame->has_result = 1;
}

static void native_string_format(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    const char *fmt = (args != NULL) ? (const char *)args[0] : NULL;
    if (fmt == NULL) { frame->result = (void *)strdup(""); frame->has_result = 1; return; }
    /* Collect args from Object[] if provided (args[1]) */
    void **fargs = NULL;
    int fargc = 0;
    if (nargs > 1 && args[1] != NULL) {
        fargs = (void **)((char *)args[1] + 8);
        fargc = *(int *)args[1];
    }
    size_t cap = 256, len = 0;
    char *buf = (char *)malloc(cap);
    buf[0] = 0;
    int argi = 0;
    for (const char *p = fmt; *p; ) {
        if (*p == '%' && p[1] != 0) {
            /* Parse format spec: %[flags][width][.precision]conversion */
            const char *sp = p + 1;
            /* skip flags */
            while (*sp == '-' || *sp == '+' || *sp == ' ' || *sp == '0' || *sp == '#') sp++;
            /* skip width */
            while (*sp >= '0' && *sp <= '9') sp++;
            /* skip precision */
            if (*sp == '.') { sp++; while (*sp >= '0' && *sp <= '9') sp++; }
            char spec = *sp;
            if (spec == 0) { /* trailing % */
                buf[len++] = *p++; if (len >= cap) { cap *= 2; buf = realloc(buf, cap); } buf[len] = 0;
                continue;
            }
            char tmp[256];
            tmp[0] = 0;
            if (spec == 's') {
                if (argi < fargc && fargs) {
                    void *v = fargs[argi++];
                    const char *s = (const char *)v;
                    int printable = (v != NULL) && ((uintptr_t)v >= (1 << 20));
                    if (printable) {
                        for (int k = 0; k < 8 && s[k]; k++) if ((unsigned char)s[k] < 32) { printable = 0; break; }
                    }
                    if (printable && s && s[0]) snprintf(tmp, sizeof(tmp), "%s", s);
                    else snprintf(tmp, sizeof(tmp), "%d", (int)(intptr_t)v);
                } else {
                    snprintf(tmp, sizeof(tmp), "null");
                }
            } else if (spec == 'd' || spec == 'i') {
                int v = (argi < fargc && fargs) ? (int)(intptr_t)fargs[argi++] : 0;
                snprintf(tmp, sizeof(tmp), "%d", v);
            } else if (spec == 'f') {
                long bits = (argi < fargc && fargs) ? (long)(intptr_t)fargs[argi++] : 0;
                double v; memcpy(&v, &bits, sizeof(v));
                snprintf(tmp, sizeof(tmp), "%g", v);
            } else if (spec == '%') {
                tmp[0] = '%'; tmp[1] = 0;
                p++; /* skip one % */
            } else {
                tmp[0] = '%'; tmp[1] = spec; tmp[2] = 0;
            }
            size_t tl = strlen(tmp);
            while (len + tl + 1 > cap) { cap *= 2; buf = (char *)realloc(buf, cap); }
            memcpy(buf + len, tmp, tl); len += tl; buf[len] = 0;
            p = sp + 1;
        } else {
            while (len + 2 > cap) { cap *= 2; buf = (char *)realloc(buf, cap); }
            buf[len++] = *p++; buf[len] = 0;
        }
    }
    frame->result = (void *)buf;
    frame->has_result = 1;
}

static void native_math_sqrt(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    long bits = (args != NULL) ? (long)(intptr_t)args[0] : 0;
    double v; memcpy(&v, &bits, sizeof(v));
    double r = sqrt(v);
    long rb; memcpy(&rb, &r, sizeof(rb));
    frame->result = (void *)(intptr_t)rb;
    frame->has_result = 1;
}

static void native_math_pow(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    long b1 = (args != NULL) ? (long)(intptr_t)args[0] : 0;
    long b2 = (nargs > 1) ? (long)(intptr_t)args[1] : 0;
    double a, b; memcpy(&a, &b1, sizeof(a)); memcpy(&b, &b2, sizeof(b));
    double r = pow(a, b);
    long rb; memcpy(&rb, &r, sizeof(rb));
    frame->result = (void *)(intptr_t)rb;
    frame->has_result = 1;
}

/* ---- ServiceLoader shim side table ---- */
#define NABU_MAX_SL 64
typedef struct {
    void *handle;
    int count;
    int idx;
    void **providers;
} nabu_sl_state_t;
static nabu_sl_state_t nabu_sl_states[NABU_MAX_SL];
static int nabu_sl_state_count = 0;

static nabu_sl_state_t *sl_find(void *h) {
    for (int i = 0; i < nabu_sl_state_count; i++) {
        if (nabu_sl_states[i].handle == h) return &nabu_sl_states[i];
    }
    return NULL;
}

static nabu_sl_state_t *sl_create(void) {
    if (nabu_sl_state_count >= NABU_MAX_SL) return NULL;
    nabu_sl_state_t *s = &nabu_sl_states[nabu_sl_state_count++];
    memset(s, 0, sizeof(*s));
    s->handle = s; /* handle = pointer to state */
    return s;
}

/* Helper: load services (same logic as NabuReflect.loadServices) */
static void sl_load_providers(nabu_sl_state_t *s, const char *iface) {
    char impls[64][256];
    memset(impls, 0, sizeof(impls));
    int cnt = 0;
    if (iface) {
        FILE *f = fopen("/tmp/opencode/services.txt", "r");
        if (f) {
            char line[512];
            while (fgets(line, sizeof(line), f) && cnt < 64) {
                char *nl = strchr(line, '\n'); if (nl) *nl = 0;
                char *eq = strchr(line, '=');
                if (!eq) continue;
                *eq = 0;
                if (strcmp(line, iface) == 0) {
                    snprintf(impls[cnt], sizeof(impls[cnt]), "%s", eq + 1);
                    cnt++;
                }
            }
            fclose(f);
        }
    }

    s->count = cnt;
    s->providers = (void **)calloc(cnt > 0 ? cnt : 1, sizeof(void *));
    for (int i = 0; i < cnt; i++) {
        char internal[256];
        snprintf(internal, sizeof(internal), "%s", impls[i]);
        for (char *p = internal; *p; p++) if (*p == '.') *p = '/';
        nabu_classfile_load_class(internal);
        int fc = nabu_interp_field_count(internal);
        void *obj = calloc(1, sizeof(nabu_object) + (size_t)(fc > 0 ? fc : 1) * sizeof(void *));
        if (obj) {
            nabu_type_info *ti = (nabu_type_info *)nabu_register_interpreted_type(internal, "java/lang/Object", 0, NULL, NULL);
            ((nabu_object *)obj)->type = ti;
            nabu_interp_method_t *m = nabu_interp_find_method(internal, "<init>", "()V");
            if (m) { void *a[1] = { obj }; nabu_interp_invoke(m, a, 1); }
        }
        s->providers[i] = obj;
    }

    s->idx = 0;
}

static void native_sl_load(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    const char *iface = class_handle_name(args != NULL ? args[0] : NULL);
    if (iface == NULL && args != NULL && args[0] != NULL) iface = (const char *)args[0];
    nabu_sl_state_t *s = sl_create();
    if (s) sl_load_providers(s, iface);
    frame->result = s ? s->handle : NULL;
    frame->has_result = 1;
}

static void native_sl_load2(nabu_interp_frame_t *frame, void **args, int nargs) {
    /* (Class, ClassLoader) → use args[0] */
    native_sl_load(frame, args, nargs);
}

static void native_sl_loadModule(nabu_interp_frame_t *frame, void **args, int nargs) {
    /* (ModuleLayer, Class) → use args[1] as service class */
    if (nargs > 1 && args != NULL) {
        void *tmp[1] = { args[1] };
        native_sl_load(frame, tmp, 1);
    } else {
        native_sl_load(frame, args, nargs);
    }
}

static void native_sl_iterator(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;

    /* return the same handle (state) */
    frame->result = (args != NULL) ? args[0] : NULL;
    frame->has_result = 1;
}

static void native_sl_hasNext(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    void *h = (args != NULL) ? args[0] : NULL;
    nabu_sl_state_t *s = sl_find(h);
    int r = (s && s->idx < s->count) ? 1 : 0;
    frame->result = (void *)(intptr_t)r;
    frame->has_result = 1;
}

static void native_sl_next(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    void *h = (args != NULL) ? args[0] : NULL;
    nabu_sl_state_t *s = sl_find(h);
    if (s && s->idx < s->count) {
        frame->result = s->providers[s->idx++];
    } else {
        frame->result = NULL;
    }
    frame->has_result = 1;
}

static void native_sb_insert(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    void *obj = (args != NULL) ? args[0] : NULL;
    int idx = (nargs > 1) ? (int)(intptr_t)args[1] : 0;
    const char *s = (nargs > 2 && args[2]) ? (const char *)args[2] : "";
    char *cur = sb_get(obj);
    if (cur == NULL) { sb_set(obj, s); frame->result = obj; frame->has_result = 1; return; }
    int len = (int)strlen(cur);
    if (idx < 0) idx = 0;
    if (idx > len) idx = len;
    int slen = (int)strlen(s);
    char *nb = (char *)malloc(len + slen + 1);
    memcpy(nb, cur, idx);
    memcpy(nb + idx, s, slen);
    memcpy(nb + idx + slen, cur + idx, len - idx + 1);
    sb_set(obj, nb);
    free(nb);
    frame->result = obj;
    frame->has_result = 1;
}

static void native_sb_delete(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    void *obj = (args != NULL) ? args[0] : NULL;
    int start = (nargs > 1) ? (int)(intptr_t)args[1] : 0;
    int end = (nargs > 2) ? (int)(intptr_t)args[2] : 0;
    char *cur = sb_get(obj);
    if (cur == NULL) { frame->result = obj; frame->has_result = 1; return; }
    int len = (int)strlen(cur);
    if (start < 0) start = 0;
    if (end > len) end = len;
    if (start > end) start = end;
    char *nb = (char *)malloc(len - (end - start) + 1);
    memcpy(nb, cur, start);
    memcpy(nb + start, cur + end, len - end + 1);
    sb_set(obj, nb);
    free(nb);
    frame->result = obj;
    frame->has_result = 1;
}

static void native_classloader_findLoadedClass(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    /* Return null (not tracked separately; auto-load handles it). */
    frame->result = NULL;
    frame->has_result = 1;
}

static void native_classloader_findClass(nabu_interp_frame_t *frame, void **args, int nargs) {
    const char *name = NULL;
    if (nargs > 1 && args != NULL && args[1] != NULL) name = (const char *)args[1];
    else if (args != NULL && args[0] != NULL) name = (const char *)args[0];
    if (name == NULL) { frame->result = NULL; frame->has_result = 1; return; }
    char internal[256];
    snprintf(internal, sizeof(internal), "%s", name);
    for (char *p = internal; *p; p++) if (*p == '.') *p = '/';
    if (nabu_classfile_load_class(internal) == 0 || nabu_classfile_load_class(name) == 0) {
        frame->result = make_class_object(name);
    } else {
        frame->result = NULL;
    }
    frame->has_result = 1;
}

static void native_cl_handle(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)args; (void)nargs;
    frame->result = (void *)strdup("system");
    frame->has_result = 1;
}

static void native_arrays_toString_int(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    int *arr = (args != NULL && args[0] != NULL) ? (int *)((char *)args[0] + 8) : NULL;
    int len = (args != NULL && args[0] != NULL) ? *(int *)args[0] : 0;
    if (arr == NULL || len <= 0) { frame->result = (void *)strdup("[]"); frame->has_result = 1; return; }
    size_t cap = 64 + (size_t)len * 8;
    char *buf = (char *)malloc(cap);
    size_t pos = 0;
    buf[pos++] = '[';
    for (int i = 0; i < len; i++) {
        if (i > 0) { buf[pos++] = ','; buf[pos++] = ' '; }
        char tmp[32]; snprintf(tmp, sizeof(tmp), "%d", arr[i]);
        size_t tl = strlen(tmp);
        memcpy(buf + pos, tmp, tl); pos += tl;
    }
    buf[pos++] = ']'; buf[pos] = 0;
    frame->result = (void *)buf;
    frame->has_result = 1;
}

static void native_arrays_toString_obj(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    void **arr = (args != NULL && args[0] != NULL) ? (void **)((char *)args[0] + 8) : NULL;
    int len = (args != NULL && args[0] != NULL) ? *(int *)args[0] : 0;
    if (arr == NULL || len <= 0) { frame->result = (void *)strdup("[]"); frame->has_result = 1; return; }
    size_t cap = 64 + (size_t)len * 16;
    char *buf = (char *)malloc(cap);
    size_t pos = 0;
    buf[pos++] = '[';
    for (int i = 0; i < len; i++) {
        if (i > 0) { buf[pos++] = ','; buf[pos++] = ' '; }
        const char *s = (const char *)arr[i];
        int printable = (s != NULL) && ((uintptr_t)s >= (1 << 20));
        if (printable) {
            for (int k = 0; k < 8 && s[k]; k++) if ((unsigned char)s[k] < 32) { printable = 0; break; }
        }
        char tmp[256];
        if (arr[i] == NULL) snprintf(tmp, sizeof(tmp), "null");
        else if (printable && s && s[0]) snprintf(tmp, sizeof(tmp), "%s", s);
        else snprintf(tmp, sizeof(tmp), "%d", (int)(intptr_t)arr[i]);
        size_t tl = strlen(tmp);
        if (pos + tl + 4 > cap) { cap *= 2; buf = (char *)realloc(buf, cap); }
        memcpy(buf + pos, tmp, tl); pos += tl;
    }
    buf[pos++] = ']'; buf[pos] = 0;
    frame->result = (void *)buf;
    frame->has_result = 1;
}

static void native_proxy_newProxyInstance(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    /* args: (ClassLoader, Class[], InvocationHandler) */
    void **ifaces = (nargs > 1 && args[1] != NULL) ? (void **)((char *)args[1] + 8) : NULL;
    int ifcount = (nargs > 1 && args[1] != NULL) ? *(int *)args[1] : 0;
    void *handler = (nargs > 2) ? args[2] : NULL;
    const char *iface = "java/lang/Object";
    if (ifcount > 0 && ifaces && ifaces[0]) {
        const char *n = class_handle_name(ifaces[0]);
        if (n) iface = n;
    }
    /* create proxy object */
    char internal[256];
    snprintf(internal, sizeof(internal), "%s", iface);
    for (char *p = internal; *p; p++) if (*p == '.') *p = '/';
    int fc = 0;
    void *obj = calloc(1, sizeof(nabu_object) + (size_t)(fc > 0 ? fc : 1) * sizeof(void *));
    if (obj != NULL) {
        nabu_type_info *ti = (nabu_type_info *)nabu_register_interpreted_type(internal, "java/lang/Object", 0, NULL, NULL);
        ((nabu_object *)obj)->type = ti;
        if (proxy_count < NABU_MAX_PROXY) {
            proxy_objs[proxy_count] = obj;
            proxy_handlers[proxy_count] = handler;
            proxy_ifaces[proxy_count] = strdup(internal);
            proxy_count++;
        }
    }
    frame->result = obj;
    frame->has_result = 1;
}

static void native_identity_double(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    /* args[0] may be double bits or a pointer */
    frame->result = (args != NULL) ? args[0] : NULL;
    frame->has_result = 1;
}

static void native_objects_checkIndex(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    int index = (args != NULL) ? (int)(intptr_t)args[0] : 0;
    int length = (nargs > 1) ? (int)(intptr_t)args[1] : 0;
    if (index < 0 || index >= length) {
        /* throw IndexOutOfBoundsException (just print and return -1) */
        fprintf(stderr, "IndexOutOfBounds: %d (len=%d)\n", index, length);
        frame->result = (void *)(intptr_t)-1;
    } else {
        frame->result = (void *)(intptr_t)index;
    }
    frame->has_result = 1;
}

static void native_string_join(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    const char *delim = (args != NULL) ? (const char *)args[0] : "";
    void **arr = (nargs > 1 && args[1] != NULL) ? (void **)((char *)args[1] + 8) : NULL;
    int len = (nargs > 1 && args[1] != NULL) ? *(int *)args[1] : 0;
    if (delim == NULL) delim = "";
    size_t cap = 256, pos = 0;
    char *buf = (char *)malloc(cap);
    buf[0] = 0;
    for (int i = 0; i < len; i++) {
        if (i > 0) {
            size_t dl = strlen(delim);
            while (pos + dl + 1 > cap) { cap *= 2; buf = (char *)realloc(buf, cap); }
            memcpy(buf + pos, delim, dl); pos += dl; buf[pos] = 0;
        }
        const char *s = arr ? (const char *)arr[i] : NULL;
        int printable = (s != NULL) && ((uintptr_t)s >= (1 << 20));
        if (printable) {
            for (int k = 0; k < 8 && s[k]; k++) if ((unsigned char)s[k] < 32) { printable = 0; break; }
        }
        char tmp[256];
        if (printable && s && s[0]) snprintf(tmp, sizeof(tmp), "%s", s);
        else snprintf(tmp, sizeof(tmp), "%d", (int)(intptr_t)(arr ? arr[i] : NULL));
        size_t tl = strlen(tmp);
        while (pos + tl + 1 > cap) { cap *= 2; buf = (char *)realloc(buf, cap); }
        memcpy(buf + pos, tmp, tl); pos += tl; buf[pos] = 0;
    }
    frame->result = (void *)buf;
    frame->has_result = 1;
}

static void native_double_parseDouble(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    const char *s = (args != NULL) ? (const char *)args[0] : NULL;
    double v = s ? atof(s) : 0.0;
    long bits; memcpy(&bits, &v, sizeof(bits));
    frame->result = (void *)(intptr_t)bits;
    frame->has_result = 1;
}

static void native_boolean_parseBoolean(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    const char *s = (args != NULL) ? (const char *)args[0] : NULL;
    int r = (s && (strcmp(s, "true") == 0)) ? 1 : 0;
    frame->result = (void *)(intptr_t)r;
    frame->has_result = 1;
}

static void native_arrays_copyOf_int(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    int *src = (args != NULL && args[0] != NULL) ? (int *)((char *)args[0] + 8) : NULL;
    int srcLen = (args != NULL && args[0] != NULL) ? *(int *)args[0] : 0;
    int newLen = (nargs > 1) ? (int)(intptr_t)args[1] : srcLen;
    if (newLen < 0) newLen = 0;
    void *arr = calloc(1, 8 + (size_t)newLen * sizeof(int));
    if (arr != NULL) {
        *(int *)arr = newLen;
        if (src && newLen > 0) {
            int *dst = (int *)((char *)arr + 8);
            int copyLen = (srcLen < newLen) ? srcLen : newLen;
            memcpy(dst, src, (size_t)copyLen * sizeof(int));
        }
    }
    frame->result = arr;
    frame->has_result = 1;
}

static void native_arrays_copyOf_obj(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    void **src = (args != NULL && args[0] != NULL) ? (void **)((char *)args[0] + 8) : NULL;
    int srcLen = (args != NULL && args[0] != NULL) ? *(int *)args[0] : 0;
    int newLen = (nargs > 1) ? (int)(intptr_t)args[1] : srcLen;
    if (newLen < 0) newLen = 0;
    void *arr = calloc(1, 8 + (size_t)newLen * sizeof(void *));
    if (arr != NULL) {
        *(int *)arr = newLen;
        void **dst = (void **)((char *)arr + 8);
        if (src && newLen > 0) {
            int copyLen = (srcLen < newLen) ? srcLen : newLen;
            memcpy(dst, src, (size_t)copyLen * sizeof(void *));
        }
    }
    frame->result = arr;
    frame->has_result = 1;
}

static void native_math_round_float(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    int bits = (args != NULL) ? (int)(intptr_t)args[0] : 0;
    float v; memcpy(&v, &bits, sizeof(v));
    frame->result = (void *)(intptr_t)(int)lroundf(v);
    frame->has_result = 1;
}

static void native_math_round_double(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    long bits = (args != NULL) ? (long)(intptr_t)args[0] : 0;
    double v; memcpy(&v, &bits, sizeof(v));
    frame->result = (void *)(intptr_t)(long)lround(v);
    frame->has_result = 1;
}

static void native_math_floor(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    long bits = (args != NULL) ? (long)(intptr_t)args[0] : 0;
    double v; memcpy(&v, &bits, sizeof(v));
    double r = floor(v);
    long rb; memcpy(&rb, &r, sizeof(rb));
    frame->result = (void *)(intptr_t)rb;
    frame->has_result = 1;
}

static void native_math_ceil(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    long bits = (args != NULL) ? (long)(intptr_t)args[0] : 0;
    double v; memcpy(&v, &bits, sizeof(v));
    double r = ceil(v);
    long rb; memcpy(&rb, &r, sizeof(rb));
    frame->result = (void *)(intptr_t)rb;
    frame->has_result = 1;
}

static void native_arrays_sort_int(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    if (args != NULL && args[0] != NULL) {
        int *arr = (int *)((char *)args[0] + 8);
        int len = *(int *)args[0];
        /* simple bubble sort */
        for (int i = 0; i < len - 1; i++) {
            for (int j = 0; j < len - 1 - i; j++) {
                if (arr[j] > arr[j + 1]) {
                    int t = arr[j]; arr[j] = arr[j + 1]; arr[j + 1] = t;
                }
            }
        }
    }
    frame->has_result = 0;
}

static void native_string_replace_str(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    const char *s = (args != NULL) ? (const char *)args[0] : NULL;
    const char *old = (nargs > 1) ? (const char *)args[1] : NULL;
    const char *nw = (nargs > 2) ? (const char *)args[2] : NULL;
    if (s == NULL) { frame->result = (void *)strdup(""); frame->has_result = 1; return; }
    if (old == NULL || old[0] == 0) { frame->result = (void *)strdup(s); frame->has_result = 1; return; }
    size_t cap = 256, len = 0;
    char *buf = (char *)malloc(cap);
    buf[0] = 0;
    size_t oldlen = strlen(old);
    size_t nwlen = nw ? strlen(nw) : 0;
    const char *p = s;
    while (*p) {
        if (strncmp(p, old, oldlen) == 0) {
            while (len + nwlen + 1 > cap) { cap *= 2; buf = (char *)realloc(buf, cap); }
            if (nw) memcpy(buf + len, nw, nwlen);
            len += nwlen; buf[len] = 0;
            p += oldlen;
        } else {
            while (len + 2 > cap) { cap *= 2; buf = (char *)realloc(buf, cap); }
            buf[len++] = *p++; buf[len] = 0;
        }
    }
    frame->result = (void *)buf;
    frame->has_result = 1;
}

static void native_noop(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)args; (void)nargs;
    frame->has_result = 0;
}

/* ---- Minimal reflection shim (NabuReflect) ---- */

static char *name_to_path(const char *name) {
    char tmp[256];
    snprintf(tmp, sizeof(tmp), "%s", name ? name : "");
    for (char *p = tmp; *p; p++) if (*p == '.') *p = '/';
    char buf[512];
    snprintf(buf, sizeof(buf), "/tmp/opencode/jtest2/%s.class", tmp);
    FILE *f = fopen(buf, "rb");
    if (f) { fclose(f); return strdup(buf); }
    snprintf(buf, sizeof(buf), "/tmp/opencode/jtest/%s.class", tmp);
    f = fopen(buf, "rb");
    if (f) { fclose(f); return strdup(buf); }
    snprintf(buf, sizeof(buf), "/tmp/opencode/jtest2/%s.class", tmp);
    return strdup(buf);
}

static void native_reflect_forName(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    const char *name = (const char *)(args != NULL ? args[0] : NULL);
    if (name == NULL) { frame->result = NULL; frame->has_result = 1; return; }
    char *path = name_to_path(name);
    if (nabu_classfile_load(path) != 0) {
        fprintf(stderr, "reflect: cannot load %s\n", path);
    }
    free(path);
    frame->result = make_class_object(name);
    frame->has_result = 1;
}

static void native_reflect_getName(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    const char *handle = class_handle_name(args != NULL ? args[0] : NULL);
    if (handle == NULL) { frame->result = NULL; frame->has_result = 1; return; }
    char *r = strdup(handle);
    for (char *p = r; *p; p++) if (*p == '/') *p = '.';
    frame->result = (void *)r;
    frame->has_result = 1;
}

static int count_params_desc(const char *desc) {
    /* reuse logic: count between ( and ) */
    if (desc == NULL) return 0;
    const char *p = strchr(desc, '(');
    if (p == NULL) return 0;
    p++;
    int n = 0;
    while (*p != ')' && *p != 0) {
        if (*p == 'L') { while (*p != ';' && *p != 0) p++; if (*p == ';') p++; n++; }
        else if (*p == '[') { while (*p == '[') p++; if (*p == 'L') { while (*p != ';' && *p != 0) p++; if (*p == ';') p++; } else p++; n++; }
        else { p++; n++; }
    }
    return n;
}

static void native_reflect_call(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    const char *cls = (const char *)(args != NULL ? args[0] : NULL);
    const char *method = (args != NULL && nargs > 1) ? (const char *)args[1] : NULL;
    const char *desc = (args != NULL && nargs > 2) ? (const char *)args[2] : NULL;
    long a0 = (args != NULL && nargs > 3) ? (long)(intptr_t)args[3] : 0;
    long a1 = (args != NULL && nargs > 4) ? (long)(intptr_t)args[4] : 0;
    if (cls == NULL || method == NULL || desc == NULL) {
        frame->result = (void *)(intptr_t)0; frame->has_result = 1; return;
    }
    int pcnt = count_params_desc(desc);
    void **cargs = (void **)malloc(sizeof(void *) * (pcnt > 0 ? pcnt : 1));
    long vals[2] = { a0, a1 };
    for (int i = 0; i < pcnt && i < 2; i++) {
        char t = 0;
        /* find i-th param type */
        const char *p = strchr(desc, '(');
        if (p) {
            p++;
            int idx = 0;
            while (idx < i && *p != ')') {
                if (*p == 'L') { while (*p != ';') p++; p++; }
                else if (*p == '[') { while (*p == '[') p++; if (*p == 'L') { while (*p != ';') p++; p++; } else p++; }
                else p++;
                idx++;
            }
            t = *p;
        }
        if (t == 'J' || t == 'D') cargs[i] = (void *)(intptr_t)vals[i];
        else if (t == 'F') { int bits; float fv = (float)vals[i]; memcpy(&bits, &fv, sizeof(bits)); cargs[i] = (void *)(intptr_t)bits; }
        else if (t == 'L' || t == '[') cargs[i] = (void *)(intptr_t)vals[i];
        else cargs[i] = (void *)(intptr_t)(int)vals[i];
    }
    /* find method */
    nabu_interp_method_t *target = nabu_interp_find_method(cls, method, desc);
    void *r = NULL;
    if (target != NULL) {
        r = nabu_interp_invoke(target, cargs, pcnt);
    } else {
        void *nf = nabu_find_native(cls, method, desc);
        if (nf != NULL) {
            nabu_interp_method_t *nm = (nabu_interp_method_t *)calloc(1, sizeof(nabu_interp_method_t));
            nm->class_name = strdup(cls);
            nm->name = strdup(method);
            nm->desc = strdup(desc);
            nm->is_native = 1;
            nm->native_fn = nf;
            nm->max_locals = 8; nm->max_stack = 8;
            r = nabu_interp_invoke(nm, cargs, pcnt);
        } else {
            fprintf(stderr, "reflect: method not found %s.%s%s\n", cls, method, desc);
        }
    }
    free(cargs);
    /* Return as long: if void return 0, else (long)(intptr_t)r */
    if (desc != NULL) {
        const char *rp = strchr(desc, ')');
        if (rp && rp[1] == 'V') r = (void *)(intptr_t)0;
    }
    frame->result = (void *)(intptr_t)(long)(intptr_t)r;
    frame->has_result = 1;
}

static void native_reflect_newInstance(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    const char *name = (const char *)(args != NULL ? args[0] : NULL);
    if (name == NULL) { frame->result = NULL; frame->has_result = 1; return; }
    char internal[256];
    snprintf(internal, sizeof(internal), "%s", name);
    for (char *p = internal; *p; p++) if (*p == '.') *p = '/';
    /* load class */
    char *path = name_to_path(name);
    nabu_classfile_load(path);
    free(path);
    int fc = nabu_interp_field_count(internal);
    void *obj = calloc(1, sizeof(nabu_object) + (size_t)(fc > 0 ? fc : 1) * sizeof(void *));
    if (obj != NULL) {
        nabu_type_info *ti = (nabu_type_info *)nabu_register_interpreted_type(internal, "java/lang/Object", 0, NULL, NULL);
        ((nabu_object *)obj)->type = ti;
        /* invoke <init>()V */
        nabu_interp_method_t *m = nabu_interp_find_method(internal, "<init>", "()V");

        if (m != NULL) {
            void *a[1] = { obj };
            nabu_interp_invoke(m, a, 1);
        }
    }
    frame->result = obj;
    frame->has_result = 1;
}

static void native_reflect_callInstance(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    void *target = (args != NULL) ? args[0] : NULL;
    const char *method = (args != NULL && nargs > 1) ? (const char *)args[1] : NULL;
    const char *desc = (args != NULL && nargs > 2) ? (const char *)args[2] : NULL;
    long a0 = (args != NULL && nargs > 3) ? (long)(intptr_t)args[3] : 0;
    long a1 = (args != NULL && nargs > 4) ? (long)(intptr_t)args[4] : 0;
    if (target == NULL || method == NULL || desc == NULL) {
        frame->result = (void *)(intptr_t)0; frame->has_result = 1; return;
    }
    const char *cname = nabu_compiled_type_name(target);
    if (cname == NULL) cname = "java/lang/Object";
    /* cname is internal (slashes) already */
    int pcnt = count_params_desc(desc); /* params excluding this */
    int total = pcnt + 1; /* includes this */
    void **cargs = (void **)malloc(sizeof(void *) * (total > 0 ? total : 1));
    cargs[0] = target;
    long vals[2] = { a0, a1 };
    for (int i = 1; i <= pcnt && (i - 1) < 2; i++) {
        char t = 0;
        const char *p = strchr(desc, '(');
        if (p) {
            p++;
            int idx = 0;
            while (idx < (i - 1) && *p != ')') {
                if (*p == 'L') { while (*p != ';') p++; p++; }
                else if (*p == '[') { while (*p == '[') p++; if (*p == 'L') { while (*p != ';') p++; p++; } else p++; }
                else p++;
                idx++;
            }
            t = *p;
        }
        int vi = i - 1;
        if (t == 'J' || t == 'D') cargs[i] = (void *)(intptr_t)vals[vi];
        else if (t == 'F') { int bits; float fv = (float)vals[vi]; memcpy(&bits, &fv, sizeof(bits)); cargs[i] = (void *)(intptr_t)bits; }
        else if (t == 'L' || t == '[') cargs[i] = (void *)(intptr_t)vals[vi];
        else cargs[i] = (void *)(intptr_t)(int)vals[vi];
    }
    nabu_interp_method_t *m = nabu_interp_find_method(cname, method, desc);
    void *r = NULL;
    if (m != NULL) {
        r = nabu_interp_invoke(m, cargs, total);
    } else {
        void *nf = nabu_find_native(cname, method, desc);
        if (nf != NULL) {
            nabu_interp_method_t *nm = (nabu_interp_method_t *)calloc(1, sizeof(nabu_interp_method_t));
            nm->class_name = strdup(cname);
            nm->name = strdup(method);
            nm->desc = strdup(desc);
            nm->is_native = 1;
            nm->native_fn = nf;
            nm->max_locals = 8; nm->max_stack = 8;
            r = nabu_interp_invoke(nm, cargs, total);
        } else {
            fprintf(stderr, "reflect: method not found %s.%s%s\n", cname, method, desc);
        }
    }
    free(cargs);
    if (desc != NULL) {
        const char *rp = strchr(desc, ')');
        if (rp && rp[1] == 'V') r = (void *)(intptr_t)0;
    }
    frame->result = (void *)(intptr_t)(long)(intptr_t)r;
    frame->has_result = 1;
}

static void native_string_length(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    const char *s = (const char *)(args != NULL ? args[0] : NULL);
    int len = s ? (int)strlen(s) : 0;
    frame->result = (void *)(intptr_t)len;
    frame->has_result = 1;
}

static void native_string_charAt(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    const char *s = (const char *)(args != NULL ? args[0] : NULL);
    int idx = (args != NULL && nargs > 1) ? (int)(intptr_t)args[1] : 0;
    int v = 0;
    if (s && idx >= 0 && idx < (int)strlen(s)) v = (unsigned char)s[idx];
    frame->result = (void *)(intptr_t)v;
    frame->has_result = 1;
}

static void native_string_equals(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    const char *a = (const char *)(args != NULL ? args[0] : NULL);
    const char *b = (args != NULL && nargs > 1) ? (const char *)args[1] : NULL;
    int r = 0;
    if (a == b) r = 1;
    else if (a != NULL && b != NULL && strcmp(a, b) == 0) r = 1;
    frame->result = (void *)(intptr_t)r;
    frame->has_result = 1;
}

static void native_string_hashCode(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    const char *s = (args != NULL) ? (const char *)args[0] : NULL;
    int h = 0;
    if (s) for (const char *p = s; *p; p++) h = 31 * h + (unsigned char)*p;
    frame->result = (void *)(intptr_t)h;
    frame->has_result = 1;
}

static void register_builtins(void) {
    static int done = 0;
    if (done) return;
    done = 1;
    /* Object constructor is a no-op in the interpreter. */
    register_native("java/lang/Object", "<init>", "()V", native_noop);
    register_native("java/lang/String", "length", "()I", native_string_length);
    register_native("java/lang/String", "charAt", "(I)C", native_string_charAt);
    register_native("java/lang/String", "equals", "(Ljava/lang/Object;)Z", native_string_equals);
    register_native("java/lang/String", "hashCode", "()I", native_string_hashCode);
    register_native("java/lang/String", "toString", "()Ljava/lang/String;", native_identity_str);
    register_native("java/lang/String", "valueOf", "(Ljava/lang/Object;)Ljava/lang/String;", native_string_valueOf_obj);
    register_native("java/lang/String", "valueOf", "(I)Ljava/lang/String;", native_integer_toString);
    /* StringBuilder */
    register_native("java/lang/StringBuilder", "<init>", "()V", native_sb_init);
    register_native("java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", native_sb_init_str);
    register_native("java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", native_sb_append_str);
    register_native("java/lang/StringBuilder", "append", "(I)Ljava/lang/StringBuilder;", native_sb_append_int);
    register_native("java/lang/StringBuilder", "append", "(J)Ljava/lang/StringBuilder;", native_sb_append_int);
    register_native("java/lang/StringBuilder", "append", "(C)Ljava/lang/StringBuilder;", native_sb_append_int);
    register_native("java/lang/StringBuilder", "append", "(Ljava/lang/Object;)Ljava/lang/StringBuilder;", native_sb_append_str);
    register_native("java/lang/StringBuilder", "toString", "()Ljava/lang/String;", native_sb_toString);
    register_native("java/lang/StringBuilder", "ensureCapacity", "(I)V", native_sb_ensureCapacity);
    register_native("java/lang/StringBuilder", "capacity", "()I", native_sb_capacity);
    register_native("java/lang/StringBuilder", "reverse", "()Ljava/lang/StringBuilder;", native_sb_reverse);
    register_native("java/lang/StringBuilder", "insert", "(ILjava/lang/String;)Ljava/lang/StringBuilder;", native_sb_insert);
    register_native("java/lang/StringBuilder", "delete", "(II)Ljava/lang/StringBuilder;", native_sb_delete);
    register_native("java/lang/String", "isEmpty", "()Z", native_string_isEmpty);
    /* Object */
    register_native("java/lang/Object", "toString", "()Ljava/lang/String;", native_object_toString);
    /* Integer */
    register_native("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", native_identity_int);
    register_native("java/lang/Integer", "toString", "(I)Ljava/lang/String;", native_integer_toString);
    /* JDK java.lang.Class intrinsics (minimal). */
    register_native("java/lang/Class", "forName0", "(Ljava/lang/String;ZLjava/lang/ClassLoader;Ljava/lang/Class;)Ljava/lang/Class;", native_class_forName0);
    register_native("java/lang/Class", "getName0", "()Ljava/lang/String;", native_class_getName0);
    register_native("java/lang/Class", "getName", "()Ljava/lang/String;", native_class_getName);
    register_native("java/lang/Class", "toString", "()Ljava/lang/String;", native_class_toString);
    register_native("java/lang/Class", "getSimpleName", "()Ljava/lang/String;", native_class_getSimpleName);
    register_native("java/lang/Object", "getClass", "()Ljava/lang/Class;", native_object_getClass);
    register_native("java/lang/Integer", "toHexString", "(I)Ljava/lang/String;", native_integer_toHexString);
    register_native("java/lang/Integer", "toString", "(II)Ljava/lang/String;", native_integer_toString_radix);
    register_native("java/lang/String", "format", "(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;", native_string_format);
    register_native("java/lang/Math", "sqrt", "(D)D", native_math_sqrt);
    register_native("java/lang/Math", "pow", "(DD)D", native_math_pow);
    register_native("java/lang/Math", "round", "(F)I", native_math_round_float);
    register_native("java/lang/Math", "round", "(D)J", native_math_round_double);
    register_native("java/lang/Math", "floor", "(D)D", native_math_floor);
    register_native("java/lang/Math", "ceil", "(D)D", native_math_ceil);
    register_native("java/lang/Integer", "parseInt", "(Ljava/lang/String;)I", native_integer_parseInt);
    /* String extra */
    register_native("java/lang/String", "indexOf", "(I)I", native_string_indexOf_char);
    register_native("java/lang/String", "indexOf", "(Ljava/lang/String;)I", native_string_indexOf_str);
    register_native("java/lang/String", "substring", "(II)Ljava/lang/String;", native_string_substring);
    register_native("java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", native_string_concat);
    register_native("java/lang/String", "startsWith", "(Ljava/lang/String;)Z", native_string_startsWith);
    register_native("java/lang/String", "endsWith", "(Ljava/lang/String;)Z", native_string_endsWith);
    register_native("java/lang/Double", "parseDouble", "(Ljava/lang/String;)D", native_double_parseDouble);
    register_native("java/lang/Boolean", "parseBoolean", "(Ljava/lang/String;)Z", native_boolean_parseBoolean);
    register_native("java/util/Objects", "equals", "(Ljava/lang/Object;Ljava/lang/Object;)Z", native_object_equals);
    register_native("java/util/Objects", "hashCode", "(Ljava/lang/Object;)I", native_object_hashCode);
    register_native("java/util/Objects", "toString", "(Ljava/lang/Object;)Ljava/lang/String;", native_object_toString);
    register_native("java/lang/String", "join", "(Ljava/lang/CharSequence;[Ljava/lang/CharSequence;)Ljava/lang/String;", native_string_join);
    /* Arrays */
    register_native("java/util/Arrays", "toString", "([I)Ljava/lang/String;", native_arrays_toString_int);
    register_native("java/util/Arrays", "toString", "([Ljava/lang/Object;)Ljava/lang/String;", native_arrays_toString_obj);
    register_native("java/lang/String", "format", "(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;", native_string_format);
    /* Object */
    register_native("java/lang/Object", "hashCode", "()I", native_object_hashCode);
    register_native("java/lang/Object", "equals", "(Ljava/lang/Object;)Z", native_object_equals);
    /* System */
    register_native("java/lang/System", "currentTimeMillis", "()J", native_system_currentTimeMillis);
    register_native("java/lang/System", "identityHashCode", "(Ljava/lang/Object;)I", native_system_identityHashCode);
    /* Long */
    register_native("java/lang/Long", "toString", "(J)Ljava/lang/String;", native_long_toString);
    register_native("java/lang/Long", "parseLong", "(Ljava/lang/String;)J", native_long_parseLong);
    register_native("java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", native_identity_double);
    /* Double */
    register_native("java/lang/Double", "toString", "(D)Ljava/lang/String;", native_double_toString);
    register_native("java/lang/String", "trim", "()Ljava/lang/String;", native_string_trim);
    register_native("java/lang/String", "toUpperCase", "()Ljava/lang/String;", native_string_toUpperCase);
    register_native("java/lang/String", "toLowerCase", "()Ljava/lang/String;", native_string_toLowerCase);
    register_native("java/lang/String", "replace", "(CC)Ljava/lang/String;", native_string_replace);
    register_native("java/lang/System", "arraycopy", "(Ljava/lang/Object;ILjava/lang/Object;II)V", native_system_arraycopy);
    /* Math */
    register_native("java/lang/Math", "abs", "(I)I", native_math_abs_int);
    register_native("java/lang/Math", "min", "(II)I", native_math_min);
    register_native("java/lang/Math", "max", "(II)I", native_math_max);
    register_native("java/lang/Math", "abs", "(J)J", native_math_abs_long);
    register_native("java/lang/Class", "getDeclaredMethods", "()[Ljava/lang/reflect/Method;", native_class_getDeclaredMethods);
    register_native("java/lang/Class", "getDeclaredConstructors", "()[Ljava/lang/reflect/Constructor;", native_class_getDeclaredConstructors);
    register_native("java/lang/Class", "getDeclaredFields", "()[Ljava/lang/reflect/Field;", native_class_getDeclaredFields);
    register_native("java/lang/reflect/Field", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", native_field_get);
    register_native("java/lang/reflect/Field", "set", "(Ljava/lang/Object;Ljava/lang/Object;)V", native_field_set);
    register_native("java/lang/reflect/Constructor", "newInstance", "([Ljava/lang/Object;)Ljava/lang/Object;", native_constructor_newInstance);
    register_native("java/lang/reflect/Method", "invoke", "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", native_method_invoke);
    register_native("jdk/internal/reflect/Reflection", "getCallerClass", "()Ljava/lang/Class;", native_reflection_getCallerClass);
    register_native("java/lang/Thread", "currentThread", "()Ljava/lang/Thread;", native_thread_currentThread);
    register_native("java/lang/Thread", "getContextClassLoader", "()Ljava/lang/ClassLoader;", native_null_return);
    register_native("java/lang/ClassLoader", "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;", native_classloader_loadClass);
    register_native("java/lang/ClassLoader", "findLoadedClass", "(Ljava/lang/String;)Ljava/lang/Class;", native_classloader_findLoadedClass);
    register_native("java/lang/ClassLoader", "findClass", "(Ljava/lang/String;)Ljava/lang/Class;", native_classloader_findClass);
    register_native("java/lang/ClassLoader", "findBootstrapClassOrNull", "(Ljava/lang/String;)Ljava/lang/Class;", native_classloader_findClass);
    register_native("java/lang/ClassLoader", "getParent", "()Ljava/lang/ClassLoader;", native_null_return);
    register_native("java/lang/ClassLoader", "findClass", "(Ljava/lang/String;)Ljava/lang/Class;", native_classloader_findClass);
    register_native("java/lang/ClassLoader", "getParent", "()Ljava/lang/ClassLoader;", native_null_return);
    register_native("java/lang/ClassLoader", "getName", "()Ljava/lang/String;", native_null_return);
    register_native("java/lang/ClassLoader", "findResource", "(Ljava/lang/String;)Ljava/net/URL;", native_null_return);
    register_native("java/lang/ClassLoader", "findResources", "(Ljava/lang/String;)Ljava/util/Enumeration;", native_null_return);
    register_native("java/lang/ClassLoader", "getResource", "(Ljava/lang/String;)Ljava/net/URL;", native_null_return);
    register_native("java/lang/ClassLoader", "getResources", "(Ljava/lang/String;)Ljava/util/Enumeration;", native_null_return);
    register_native("java/lang/ClassLoader", "findResource", "(Ljava/lang/String;)Ljava/net/URL;", native_null_return);
    register_native("java/lang/ClassLoader", "findResources", "(Ljava/lang/String;)Ljava/util/Enumeration;", native_null_return);
    /* Module-based ServiceLoader */
    register_native("java/util/ServiceLoader", "load", "(Ljava/lang/ModuleLayer;Ljava/lang/Class;)Ljava/util/ServiceLoader;", native_sl_loadModule);
    /* Arrays */
    register_native("java/util/Arrays", "copyOf", "([II)[I", native_arrays_copyOf_int);
    register_native("java/util/Arrays", "copyOf", "([Ljava/lang/Object;I)[Ljava/lang/Object;", native_arrays_copyOf_obj);
    register_native("java/util/Arrays", "sort", "([I)V", native_arrays_sort_int);
    register_native("java/lang/String", "replace", "(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String;", native_string_replace_str);
    register_native("java/lang/ClassLoader", "getSystemClassLoader", "()Ljava/lang/ClassLoader;", native_cl_handle);
    /* ServiceLoader shim */
    register_native("java/util/ServiceLoader", "load", "(Ljava/lang/Class;)Ljava/util/ServiceLoader;", native_sl_load);
    register_native("java/util/ServiceLoader", "load", "(Ljava/lang/Class;Ljava/lang/ClassLoader;)Ljava/util/ServiceLoader;", native_sl_load2);
    register_native("java/util/ServiceLoader", "iterator", "()Ljava/util/Iterator;", native_sl_iterator);
    register_native("java/util/Iterator", "hasNext", "()Z", native_sl_hasNext);
    register_native("java/util/Iterator", "next", "()Ljava/lang/Object;", native_sl_next);
    /* BootLoader */
    register_native("jdk/internal/loader/BootLoader", "loadClassOrNull", "(Ljava/lang/String;)Ljava/lang/Class;", native_bootloader_loadClassOrNull);
    register_native("jdk/internal/loader/BootLoader", "loadClass", "(Ljava/lang/Module;Ljava/lang/String;)Ljava/lang/Class;", native_bootloader_loadClass);
    register_native("jdk/internal/loader/BootLoader", "getUnnamedModule", "()Ljava/lang/Module;", native_null_return);
    register_native("jdk/internal/loader/BootLoader", "hasClassPath", "()Z", native_true);
    /* ClassLoaders */
    register_native("jdk/internal/loader/ClassLoaders", "bootLoader", "()Ljava/lang/ClassLoader;", native_null_return);
    register_native("jdk/internal/loader/ClassLoaders", "platformClassLoader", "()Ljava/lang/ClassLoader;", native_null_return);
    register_native("jdk/internal/loader/ClassLoaders", "appClassLoader", "()Ljava/lang/ClassLoader;", native_null_return);
    register_native("jdk/internal/loader/ClassLoaders", "bootLoader", "()Ljava/lang/ClassLoader;", native_null_return);
    register_native("jdk/internal/loader/ClassLoaders", "platformClassLoader", "()Ljava/lang/ClassLoader;", native_null_return);
    /* ServicesCatalog */
    register_native("jdk/internal/module/ServicesCatalog", "create", "()Ljdk/internal/module/ServicesCatalog;", native_null_return);
    register_native("jdk/internal/module/ServicesCatalog", "create", "(Ljava/lang/ModuleLayer;)Ljdk/internal/module/ServicesCatalog;", native_null_return);
    register_native("jdk/internal/module/ServicesCatalog", "getServicesCatalog", "(Ljava/lang/ClassLoader;)Ljdk/internal/module/ServicesCatalog;", native_null_return);
    register_native("jdk/internal/module/ServicesCatalog", "getServicesCatalogOrNull", "(Ljava/lang/ClassLoader;)Ljdk/internal/module/ServicesCatalog;", native_null_return);
    register_native("jdk/internal/module/ServicesCatalog", "putServicesCatalog", "(Ljava/lang/ClassLoader;Ljdk/internal/module/ServicesCatalog;)V", native_noop);
    register_native("jdk/internal/module/ServicesCatalog", "findServices", "(Ljava/lang/String;)Ljava/util/List;", native_null_return);
    register_native("jdk/internal/module/ServicesCatalog", "register", "(Ljava/lang/Module;)V", native_noop);
    register_native("java/util/Objects", "requireNonNull", "(Ljava/lang/Object;)Ljava/lang/Object;", native_identity);
    register_native("java/util/Objects", "checkIndex", "(II)I", native_objects_checkIndex);
    register_native("java/lang/ref/SoftReference", "get", "()Ljava/lang/Object;", native_null_return);
    register_native("java/lang/System", "getSecurityManager", "()Ljava/lang/SecurityManager;", native_null_return);
    register_native("java/lang/ClassLoader", "getClassLoader", "(Ljava/lang/Class;)Ljava/lang/ClassLoader;", native_null_return);
    register_native("java/lang/System", "currentTimeMillis", "()J", native_zero_long);
    register_native("NabuReflect", "forName", "(Ljava/lang/String;)Ljava/lang/Object;", native_reflect_forName);
    register_native("NabuReflect", "getName", "(Ljava/lang/Object;)Ljava/lang/String;", native_reflect_getName);
    register_native("NabuReflect", "call", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;JJ)J", native_reflect_call);
    register_native("NabuReflect", "newInstance", "(Ljava/lang/String;)Ljava/lang/Object;", native_reflect_newInstance);
    register_native("NabuReflect", "callInstance", "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;JJ)J", native_reflect_callInstance);
    register_native("NabuReflect", "loadServices", "(Ljava/lang/String;)[Ljava/lang/Object;", native_reflect_loadServices);
    register_native("NabuReflect", "newProxy", "(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;", native_reflect_newProxy);
    register_native("NabuReflect", "isProxy", "(Ljava/lang/Object;)Z", native_reflect_isProxy);
    register_native("NabuReflect", "getInvocationHandler", "(Ljava/lang/Object;)Ljava/lang/Object;", native_reflect_getInvocationHandler);
    /* Also on java/lang/reflect/Proxy for API compat */
    register_native("java/lang/reflect/Proxy", "isProxyClass", "(Ljava/lang/Class;)Z", native_reflect_isProxyClass);
    register_native("java/lang/reflect/Proxy", "getInvocationHandler", "(Ljava/lang/Object;)Ljava/lang/Object;", native_reflect_getInvocationHandler);
    register_native("java/lang/reflect/Proxy", "newProxyInstance", "(Ljava/lang/ClassLoader;[Ljava/lang/Class;Ljava/lang/reflect/InvocationHandler;)Ljava/lang/Object;", native_proxy_newProxyInstance);
    register_native("java/io/PrintStream", "println", "(I)V", native_println_int);
    register_native("java/io/PrintStream", "println", "(Ljava/lang/String;)V", native_println_str);
    register_native("java/io/PrintStream", "println", "(Ljava/lang/Object;)V", native_println_obj);
    register_native("java/io/PrintStream", "println", "(C)V", native_println_char);
    register_native("java/io/PrintStream", "println", "(J)V", native_println_long);
    register_native("java/io/PrintStream", "println", "(F)V", native_println_float);
    register_native("java/io/PrintStream", "println", "(D)V", native_println_double);
    register_native("java/io/PrintStream", "println", "(Z)V", native_println_byte);
    register_native("java/io/PrintStream", "println", "(B)V", native_println_byte);
    register_native("java/io/PrintStream", "println", "(S)V", native_println_byte);
    register_native("java/io/PrintStream", "print", "(I)V", native_print_int);
    register_native("java/io/PrintStream", "print", "(Ljava/lang/String;)V", native_print_str);
}

/* Sentinel for System.out */
static void *stdout_sentinel = (void *)0x570D007;

/* ---- Class loading ---- */

/* Search classpath for a .class file given an internal class name (slashes). */
static char *nabu_find_class_file(const char *internal_name) {
    if (internal_name == NULL) return NULL;
    char tmp[512];
    snprintf(tmp, sizeof(tmp), "%s", internal_name);
    /* normalize: if dots, convert to slashes */
    for (char *p = tmp; *p; p++) if (*p == '.') *p = '/';
    /* candidate base dirs */
    const char *bases[] = {
        "/tmp/opencode/jtest2",
        "/tmp/opencode/jtest",
        "/tmp/opencode/jdkclasses/classes",
        NULL
    };
    /* also NABU_CLASSPATH env (colon-separated) */
    char *env_cp = getenv("NABU_CLASSPATH");
    char env_buf[4096];
    if (env_cp) snprintf(env_buf, sizeof(env_buf), "%s", env_cp);
    else env_buf[0] = 0;

    /* Try bases first */
    for (int b = 0; bases[b] != NULL; b++) {
        char buf[1024];
        snprintf(buf, sizeof(buf), "%s/%s.class", bases[b], tmp);
        FILE *f = fopen(buf, "rb");
        if (f) { fclose(f); return strdup(buf); }
    }
    /* Try env classpath */
    if (env_buf[0]) {
        char *save = NULL;
        char *tok = strtok_r(env_buf, ":", &save);
        while (tok) {
            char buf[1024];
            snprintf(buf, sizeof(buf), "%s/%s.class", tok, tmp);
            FILE *f = fopen(buf, "rb");
            if (f) { fclose(f); /* restore env_buf? already consumed */ return strdup(buf); }
            tok = strtok_r(NULL, ":", &save);
        }
    }
    return NULL;
}

/* ---- Interface registry ---- */
#define NABU_MAX_IFACE_ENTRIES 1024
typedef struct { char *class_name; char **interfaces; int count; } nabu_iface_entry_t;
static nabu_iface_entry_t nabu_iface_table[NABU_MAX_IFACE_ENTRIES];
static int nabu_iface_count = 0;

void nabu_register_interfaces(const char *class_name, char **ifaces, int count) {
    if (class_name == NULL || count <= 0) return;
    if (nabu_iface_count >= NABU_MAX_IFACE_ENTRIES) return;
    /* check duplicate */
    for (int i = 0; i < nabu_iface_count; i++) {
        if (strcmp(nabu_iface_table[i].class_name, class_name) == 0) {
            /* update */
            nabu_iface_table[i].interfaces = ifaces;
            nabu_iface_table[i].count = count;
            return;
        }
    }
    nabu_iface_table[nabu_iface_count].class_name = strdup(class_name);
    nabu_iface_table[nabu_iface_count].interfaces = ifaces;
    nabu_iface_table[nabu_iface_count].count = count;
    nabu_iface_count++;
}

int nabu_get_interface_count(const char *class_name) {
    for (int i = 0; i < nabu_iface_count; i++) {
        if (strcmp(nabu_iface_table[i].class_name, class_name) == 0) return nabu_iface_table[i].count;
    }
    return 0;
}

char **nabu_get_interfaces(const char *class_name) {
    for (int i = 0; i < nabu_iface_count; i++) {
        if (strcmp(nabu_iface_table[i].class_name, class_name) == 0) return nabu_iface_table[i].interfaces;
    }
    return NULL;
}

int nabu_classfile_load_class(const char *internal_name) {
    /* Skip if already loaded (check if any method registered for this class). */
    if (nabu_interp_find_method(internal_name, NULL, NULL) != NULL) return 0;
    char *path = nabu_find_class_file(internal_name);
    if (path == NULL) return -1;
    int rc = nabu_classfile_load(path);
    free(path);
    return rc;
}

int nabu_classfile_load(const char *path) {
    register_builtins();

    FILE *f = fopen(path, "rb");
    if (f == NULL) {
        fprintf(stderr, "classfile: cannot open %s\n", path);
        return -1;
    }
    fseek(f, 0, SEEK_END);
    long sz = ftell(f);
    fseek(f, 0, SEEK_SET);
    unsigned char *buf = (unsigned char *)malloc(sz);
    if (fread(buf, 1, sz, f) != (size_t)sz) {
        fclose(f); free(buf); return -1;
    }
    fclose(f);

    const unsigned char *p = buf;
    /* magic + version */
    unsigned int magic = rd_u4(p); p += 4;
    if (magic != 0xCAFEBABE) {
        fprintf(stderr, "classfile: bad magic in %s\n", path);
        free(buf); return -1;
    }
    p += 4; /* minor + major */

    /* Constant pool */
    int cp_count = rd_u2(p); p += 2;
    nabu_cp *cp = (nabu_cp *)calloc(1, sizeof(nabu_cp));
    cp->count = cp_count;
    cp->entries = (nabu_cp_entry *)calloc(cp_count, sizeof(nabu_cp_entry));
    for (int i = 1; i < cp_count; i++) {
        unsigned char tag = *p++;
        cp->entries[i].tag = tag;
        switch (tag) {
            case 1: { /* Utf8 */
                int len = rd_u2(p); p += 2;
                char *s = (char *)malloc(len + 1);
                memcpy(s, p, len);
                s[len] = 0;
                cp->entries[i].utf8 = s;
                p += len;
                break;
            }
            case 3: /* Integer */
            case 4: /* Float */
                cp->entries[i].int_val = (int)rd_u4(p);
                p += 4;
                break;
            case 5: /* Long */
            case 6: /* Double */ {
                /* takes two slots; store raw 64-bit bits in long_val */
                unsigned long long bits = 0;
                for (int b = 0; b < 8; b++) bits = (bits << 8) | p[b];
                cp->entries[i].long_val = (long)bits;
                p += 8;
                i++; /* skip next slot */
                break;
            }
            case 7: /* Class */
            case 8: /* String */
            case 19: /* Module */
            case 20: /* Package */
                cp->entries[i].name_index = rd_u2(p); /* for String, this is string_index */
                if (tag == 8) cp->entries[i].str_index = cp->entries[i].name_index;
                p += 2;
                break;
            case 9:  /* Fieldref */
            case 10: /* Methodref */
            case 11: /* InterfaceMethodref */
                cp->entries[i].ref_class = rd_u2(p); p += 2;
                cp->entries[i].ref_nat = rd_u2(p); p += 2;
                break;
            case 12: /* NameAndType */
                cp->entries[i].nat_name = rd_u2(p); p += 2;
                cp->entries[i].nat_desc = rd_u2(p); p += 2;
                break;
            case 15: /* MethodHandle */
                cp->entries[i].int_val = *p; /* kind */
                p += 1;
                cp->entries[i].ref_nat = rd_u2(p); /* reference_index */
                p += 2;
                break;
            case 16: /* MethodType */
                cp->entries[i].name_index = rd_u2(p); /* descriptor_index */
                p += 2;
                break;
            case 17: /* Dynamic */
            case 18: /* InvokeDynamic */
                cp->entries[i].ref_class = rd_u2(p); p += 2; /* bootstrap_method_attr_index */
                cp->entries[i].ref_nat = rd_u2(p); p += 2; /* name_and_type_index */
                break;
            default:
                fprintf(stderr, "classfile: unknown cp tag %d\n", tag);
                /* bail */
                cp_free(cp); free(buf); return -1;
        }
    }

    /* access flags, this_class, super_class, interfaces */
    p += 2; /* access_flags */
    int this_class = rd_u2(p); p += 2;
    char *this_class_name = nabu_cp_class_name(cp, this_class);
    if (this_class_name == NULL) this_class_name = strdup("Unknown");
    else this_class_name = strdup(this_class_name);

    int super_class = rd_u2(p); p += 2;
    char *super_name = (super_class > 0) ? nabu_cp_class_name(cp, super_class) : NULL;
    if (super_name) super_name = strdup(super_name);
    int iface_count = rd_u2(p); p += 2;

    /* Read interface names. */
    char **iface_names = NULL;
    if (iface_count > 0) {
        iface_names = (char **)calloc(iface_count, sizeof(char *));
        for (int i = 0; i < iface_count; i++) {
            int ic = rd_u2(p); p += 2;
            char *in = (ic > 0) ? nabu_cp_class_name(cp, ic) : NULL;
            iface_names[i] = in ? strdup(in) : NULL;
        }
    }


    /* Fields */
    int field_count = rd_u2(p); p += 2;
    /* Count existing fields for this class (for index assignment). */
    int base_field_idx = nabu_interp_field_count(this_class_name);
    for (int i = 0; i < field_count; i++) {
        p += 2; /* access_flags */
        int fname_idx = rd_u2(p); p += 2;
        int fdesc_idx = rd_u2(p); p += 2;
        char *fname = nabu_cp_utf8(cp, fname_idx);
        /* register field */
        nabu_interp_register_field(this_class_name, fname != NULL ? fname : "?", base_field_idx + i);
        int attr_count = rd_u2(p); p += 2;
        int saved_et_count = 0;
        const unsigned char *saved_exc_ptr = NULL;
        for (int a = 0; a < attr_count; a++) {
            p += 2; /* attr_name_index */
            unsigned int alen = rd_u4(p); p += 4;
            p += alen;
        }
    }

    /* Methods */
    int method_count = rd_u2(p); p += 2;
    for (int i = 0; i < method_count; i++) {
        p += 2; /* access_flags */
        int name_idx = rd_u2(p); p += 2;
        int desc_idx = rd_u2(p); p += 2;
        char *mname = nabu_cp_utf8(cp, name_idx);
        char *mdesc = nabu_cp_utf8(cp, desc_idx);
        if (mname == NULL) mname = strdup("?");
        else mname = strdup(mname);
        if (mdesc == NULL) mdesc = strdup("?");
        else mdesc = strdup(mdesc);

        int attr_count = rd_u2(p); p += 2;
        unsigned char *code_bytes = NULL;
        int code_len = 0;
        int max_stack = 16, max_locals = 16;
        int saved_et_count = 0;
        const unsigned char *saved_exc_ptr = NULL;
        for (int a = 0; a < attr_count; a++) {
            int attr_name_idx = rd_u2(p); p += 2;
            unsigned int alen = rd_u4(p); p += 4;
            char *attr_name = nabu_cp_utf8(cp, attr_name_idx);
            if (attr_name != NULL && strcmp(attr_name, "Code") == 0) {
                max_stack = rd_u2(p); p += 2;
                max_locals = rd_u2(p); p += 2;
                code_len = (int)rd_u4(p); p += 4;
                code_bytes = (unsigned char *)malloc(code_len);
                memcpy(code_bytes, p, code_len);
                p += code_len;
                /* exception table */
                int et_count = rd_u2(p); p += 2;
                const unsigned char *exc_ptr = p;
                p += et_count * 8;
                /* store for later (after method creation) */
                saved_et_count = et_count;
                saved_exc_ptr = exc_ptr;
                /* code attributes */
                int ca_count = rd_u2(p); p += 2;
                for (int c = 0; c < ca_count; c++) {
                    p += 2; /* name */
                    unsigned int calen = rd_u4(p); p += 4;
                    p += calen;
                }
            } else {
                p += alen;
            }
        }

        /* Create method. */
        nabu_interp_method_t *m = (nabu_interp_method_t *)calloc(1, sizeof(nabu_interp_method_t));
        m->class_name = strdup(this_class_name);
        m->name = mname;
        m->desc = mdesc;
        m->code = code_bytes;
        m->code_len = code_len;
        m->max_locals = max_locals;
        m->max_stack = max_stack;
        m->is_native = 0;
        m->native_fn = NULL;
        m->const_pool = cp; /* share cp (owned by class; leaked per class but fine) */


        /* Patch bytecode: resolve invoke* and getstatic/putstatic etc. */
        if (code_bytes != NULL) {
            int i = 0;
            while (i < code_len) {
                unsigned char op = code_bytes[i];
                /* Determine instruction length for skipping. */
                int skip = 1;
                switch (op) {
                    case 0x10: skip = 2; break; /* bipush */
                    case 0x11: skip = 3; break; /* sipush */
                    case 0x12: skip = 2; break; /* ldc */
                    case 0x13: skip = 3; break; /* ldc_w */
                    case 0x14: skip = 3; break; /* ldc2_w */
                    case 0x15: case 0x16: case 0x17: case 0x18: case 0x19:
                    case 0x36: case 0x37: case 0x38: case 0x39: case 0x3a:
                        skip = 2; break; /* xload/xstore */
                    case 0x84: skip = 3; break; /* iinc */
                    case 0x99: case 0x9a: case 0x9b: case 0x9c: case 0x9d: case 0x9e:
                    case 0x9f: case 0xa0: case 0xa1: case 0xa2: case 0xa3: case 0xa4:
                    case 0xa5: case 0xa6: case 0xa7: case 0xa8:
                        skip = 3; break; /* if/goto/jsr */
                    case 0xaa: /* tableswitch */ {
                        /* compute */
                        int pad = (4 - (i % 4)) % 4; /* not exact; JVM pads to 4-byte boundary relative to method start? Actually relative to instruction start */
                        /* Simplified: skip handled below; for patching we don't need to patch inside tableswitch */
                        /* Find next instruction: parse */
                        int base = i;
                        int j = i + 1;
                        /* padding to 4-byte boundary relative to method start? JVM: padding so default begins at 4-byte boundary */
                        while ((j - base) % 4 != 0) j++;
                        j += 4; /* default */
                        (void)j;
                        /* For skeleton, just skip to end conservatively: break out */
                        i = code_len;
                        continue;
                    }
                    case 0xab: /* lookupswitch */ {
                        i = code_len;
                        continue;
                    }
                    case 0xb2: case 0xb3: case 0xb4: case 0xb5:
                    case 0xb6: case 0xb7: case 0xb8:
                    case 0xbb: case 0xbc: case 0xbd: case 0xbe: case 0xbf:
                        skip = 3; break; /* invoke/new/etc. with 2-byte index (except multianewarray 0xc5 = 4) */
                    case 0xba: skip = 5; break; /* invokedynamic */
                    case 0xb9: skip = 5; break; /* invokeinterface */
                    case 0xc5: skip = 4; break; /* multianewarray */
                     case 0xc6: case 0xc7: skip = 3; break; /* ifnull/ifnonnull */
                    case 0xc8: case 0xc9: skip = 5; break; /* goto_w/jsr_w */
                    case 0xcc: skip = 2; break; /* breakpoint? */
                    default: skip = 1; break;
                }

                i += skip;
            }
        }

        /* Exception table. */
        m->exc_count = saved_et_count;
        if (saved_et_count > 0 && saved_exc_ptr != NULL) {
            m->exc_start_pc = (int *)malloc(sizeof(int) * saved_et_count);
            m->exc_end_pc = (int *)malloc(sizeof(int) * saved_et_count);
            m->exc_handler_pc = (int *)malloc(sizeof(int) * saved_et_count);
            m->exc_type_idx = (int *)malloc(sizeof(int) * saved_et_count);
            const unsigned char *pp = saved_exc_ptr;
            for (int e = 0; e < saved_et_count; e++) {
                m->exc_start_pc[e] = rd_u2(pp); pp += 2;
                m->exc_end_pc[e] = rd_u2(pp); pp += 2;
                m->exc_handler_pc[e] = rd_u2(pp); pp += 2;
                m->exc_type_idx[e] = rd_u2(pp); pp += 2;
            }
        } else {
            m->exc_start_pc = NULL;
            m->exc_end_pc = NULL;
            m->exc_handler_pc = NULL;
            m->exc_type_idx = NULL;
        }

        /* Register method. */
        nabu_interp_register_method(m);
    }

    /* Store cp on all methods of this class? We set const_pool per method above. Good. */
    /* Don't free cp; methods reference it. */

    /* Class-level attributes (after methods). */
    if (p < buf + sz) {
        int class_attr_count = rd_u2(p); p += 2;
        nabu_bootstrap_methods_t *bsm = NULL;
        for (int a = 0; a < class_attr_count; a++) {
            int aname = rd_u2(p); p += 2;
            unsigned int alen = rd_u4(p); p += 4;
            char *attr_name = nabu_cp_utf8(cp, aname);
            if (attr_name != NULL && strcmp(attr_name, "BootstrapMethods") == 0) {
                int num_bs = rd_u2(p); p += 2;
                bsm = (nabu_bootstrap_methods_t *)calloc(1, sizeof(nabu_bootstrap_methods_t));
                bsm->count = num_bs;
                bsm->methods = (nabu_bootstrap_method_t *)calloc(num_bs, sizeof(nabu_bootstrap_method_t));
                for (int b = 0; b < num_bs; b++) {
                    p += 2; /* bootstrap_method_ref (method handle CP) */
                    int num_args = rd_u2(p); p += 2;
                    bsm->methods[b].num_static_args = num_args;
                    bsm->methods[b].static_args = (char **)calloc(num_args > 0 ? num_args : 1, sizeof(char *));
                    bsm->methods[b].bootstrap_name = NULL;
                    for (int k = 0; k < num_args; k++) {
                        int arg_cp = rd_u2(p); p += 2;
                        /* Resolve: if String CP, get utf8. */
                        char *s = NULL;
                        if (arg_cp > 0 && arg_cp < cp->count) {
                            nabu_cp_entry *ae = &cp->entries[arg_cp];
                            if (ae->tag == 8) { /* String */
                                s = nabu_cp_utf8(cp, ae->str_index);
                                if (s) s = strdup(s);
                            } else if (ae->tag == 3) { /* Integer */
                                char buf[32];
                                snprintf(buf, sizeof(buf), "INT:%d", ae->int_val);
                                s = strdup(buf);
                            } else if (ae->tag == 1) { /* Utf8 */
                                s = strdup(ae->utf8);
                            } else if (ae->tag == 16) { /* MethodType */
                                char *desc = (ae->name_index > 0) ? nabu_cp_utf8(cp, ae->name_index) : NULL;
                                char buf[512];
                                snprintf(buf, sizeof(buf), "MT:%s", desc ? desc : "?");
                                s = strdup(buf);
                            } else if (ae->tag == 15) { /* MethodHandle */
                                int ref = ae->ref_nat;
                                char *cname = NULL, *mname = NULL, *mdesc = NULL;
                                if (ref > 0 && ref < cp->count) {
                                    nabu_cp_entry *re = &cp->entries[ref];
                                    if (re->tag == 10 || re->tag == 9 || re->tag == 11) {
                                        cname = nabu_cp_class_name(cp, re->ref_class);
                                        int nat = re->ref_nat;
                                        if (nat > 0) {
                                            mname = nabu_cp_utf8(cp, cp->entries[nat].nat_name);
                                            mdesc = nabu_cp_utf8(cp, cp->entries[nat].nat_desc);
                                        }
                                    }
                                }
                                char buf[1024];
                                snprintf(buf, sizeof(buf), "MH:%s/%s%s", cname?cname:"?", mname?mname:"?", mdesc?mdesc:"");
                                s = strdup(buf);
                            }
                        }
                        bsm->methods[b].static_args[k] = s;
                    }
                }
                cp->bootstrap_methods = bsm;
            } else {
                p += alen;
            }
        }
    }

    /* Register interfaces. */
    if (iface_count > 0 && iface_names != NULL) {
        nabu_register_interfaces(this_class_name, iface_names, iface_count);
    }

    /* Build itable entries: for each interface, enumerate its methods and find concrete impl. */
    if (iface_count > 0 && iface_names != NULL) {
        for (int i = 0; i < iface_count; i++) {
            if (iface_names[i] == NULL) continue;
            nabu_classfile_load_class(iface_names[i]);
            nabu_interp_method_t **imethods = (nabu_interp_method_t **)malloc(sizeof(nabu_interp_method_t *) * 256);
            int icnt = nabu_interp_methods_for_class(iface_names[i], imethods, 256);
            for (int j = 0; j < icnt; j++) {
                nabu_interp_method_t *im = imethods[j];
                if (im == NULL || im->name == NULL) continue;
                nabu_interp_method_t *concrete = nabu_interp_find_method(this_class_name, im->name, im->desc);
                if (concrete == NULL) {
                    void *nf = nabu_find_native(this_class_name, im->name, im->desc);
                    if (nf != NULL) {
                        nabu_interp_method_t *nm = (nabu_interp_method_t *)calloc(1, sizeof(nabu_interp_method_t));
                        nm->class_name = strdup(this_class_name);
                        nm->name = strdup(im->name);
                        nm->desc = strdup(im->desc);
                        nm->is_native = 1;
                        nm->native_fn = nf;
                        nm->max_locals = 8; nm->max_stack = 8;
                        concrete = nm;
                    }
                }
                if (concrete != NULL) {
                    itable_insert(this_class_name, iface_names[i], im->name, im->desc,
                                  concrete->is_native ? NULL : concrete,
                                  concrete->is_native ? concrete->native_fn : NULL,
                                  concrete->is_native);
                }
            }
            free(imethods);
        }
    }

    /* Auto-load superclass. */
    if (super_name != NULL) {
        nabu_classfile_load_class(super_name);
    }

    free(super_name);
    free(this_class_name);
    free(buf);
    return 0;
}
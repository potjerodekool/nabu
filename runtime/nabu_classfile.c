#include "nabu_interpreter.h"
#include "nabu_runtime.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ctype.h>
#include <math.h>
#ifdef _WIN32
#include <windows.h>
#ifndef strtok_r
#define strtok_r(s, d, p) strtok_s((s), (d), (p))
#endif
#else
#include <sys/time.h>
#endif
#include <math.h>

/* ---- Constant pool entry (defined in nabu_interpreter.h) ---- */

/* Developer fixtures (jdkclasses, jtest, services.txt): $NABU_DEV_DIR, default /tmp/opencode. */
static const char *nabu_dev_dir(void) {
    const char *d = getenv("NABU_DEV_DIR");
    return (d && *d) ? d : "/tmp/opencode";
}

/* Path below the developer directory, in a static buffer (single-threaded interpreter paths). */
static const char *nabu_dev_path(const char *rel) {
    static char buf[1024];
    snprintf(buf, sizeof(buf), "%s/%s", nabu_dev_dir(), rel);
    return buf;
}

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
    char *desc;
    int access;
} nabu_interp_field_t;

static nabu_interp_field_t *fields = NULL;
static int field_count_g = 0;
static int field_cap = 0;

void nabu_interp_register_field_ex(const char *cls, const char *name, int index, const char *desc, int access) {
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
    fields[field_count_g].desc = desc ? strdup(desc) : NULL;
    fields[field_count_g].access = access;
    field_count_g++;
}

void nabu_interp_register_field(const char *cls, const char *name, int index) {
    nabu_interp_register_field_ex(cls, name, index, NULL, 1);
}

/* A field of the class or, when it is not declared there, of a superclass (a subclass names the field through its own type). */
int nabu_interp_field_index(const char *class_name, const char *field_name) {
    for (int depth = 0; class_name != NULL && depth < 64; depth++) {
        for (int i = 0; i < field_count_g; i++) {
            if (strcmp(fields[i].class_name, class_name) == 0 &&
                strcmp(fields[i].name, field_name) == 0) {
                return fields[i].index;
            }
        }
        class_name = nabu_class_super_name(class_name);
    }
    return -1;
}

/* The fields that the class declares itself. */
static int own_field_count(const char *class_name) {
    int maxidx = -1;
    for (int i = 0; i < field_count_g; i++) {
        if (strcmp(fields[i].class_name, class_name) == 0) {
            if (fields[i].index > maxidx) maxidx = fields[i].index;
        }
    }
    return maxidx + 1;
}

/* The slots of an object of the class: its own fields come after those of its superclasses. */
int nabu_interp_field_count(const char *class_name) {
    for (int depth = 0; class_name != NULL && depth < 64; depth++) {
        int own = own_field_count(class_name);
        if (own > 0) return own;
        class_name = nabu_class_super_name(class_name);
    }
    return 0;
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
    int idx = (nargs > 1) ? 1 : 0;
    void *v = args != NULL ? args[idx] : NULL;
    char *text = nabu_interp_object_to_string(v);
    printf("%s\n", text);
    free(text);
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
    int idx = (nargs > 1) ? 1 : 0;
    int bits = (int)(intptr_t)(args != NULL ? args[idx] : NULL);
    float v; memcpy(&v, &bits, sizeof(v));
    char *text = nabu_interp_float_to_string(v);
    printf("%s\n", text);
    free(text);
    frame->has_result = 0;
}

static void native_println_double(nabu_interp_frame_t *frame, void **args, int nargs) {
    int idx = (nargs > 1) ? 1 : 0;
    long bits = (long)(intptr_t)(args != NULL ? args[idx] : NULL);
    double v; memcpy(&v, &bits, sizeof(v));
    char *text = nabu_interp_double_to_string(v);
    printf("%s\n", text);
    free(text);
    frame->has_result = 0;
}

static void native_println_boolean(nabu_interp_frame_t *frame, void **args, int nargs) {
    int idx = (nargs > 1) ? 1 : 0;
    puts(((int)(intptr_t)(args != NULL ? args[idx] : NULL)) != 0 ? "true" : "false");
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
/* One Class object per class name, so that == and the identity of getClass() and X.class hold. Without the JDK's Class.class
 * a class is its name (a char *), interned the same way. */
typedef struct { char *name; void *obj; } class_cache_t;
static class_cache_t *class_cache = NULL;
static int class_cache_count = 0, class_cache_cap = 0;

static void *make_class_object(const char *name) {
    if (name == NULL) return NULL;
    for (int i = 0; i < class_cache_count; i++) if (strcmp(class_cache[i].name, name) == 0) return class_cache[i].obj;
    nabu_classfile_load(nabu_dev_path("jdkclasses/classes/java/lang/Class.class"));
    int fc = nabu_interp_field_count("java/lang/Class");
    int fi_name = nabu_interp_field_index("java/lang/Class", "name");
    void *obj;
    if (fi_name < 0) {
        obj = strdup(name);
    } else {
        obj = calloc(1, sizeof(nabu_object) + (size_t)(fc > 0 ? fc : 1) * sizeof(void *));
        if (obj == NULL) return NULL;
        nabu_type_info *ti = (nabu_type_info *)nabu_register_interpreted_type("java/lang/Class", "java/lang/Object", 0, NULL, NULL);
        ((nabu_object *)obj)->type = ti;
        void **fields = (void **)((char *)obj + sizeof(nabu_object));
        fields[fi_name] = (void *)strdup(name);
    }
    if (class_cache_count >= class_cache_cap) {
        class_cache_cap = class_cache_cap ? class_cache_cap * 2 : 64;
        class_cache = (class_cache_t *)realloc(class_cache, sizeof(class_cache_t) * (size_t)class_cache_cap);
    }
    class_cache[class_cache_count].name = strdup(name);
    class_cache[class_cache_count].obj = obj;
    class_cache_count++;
    return obj;
}

void *nabu_class_object_for_name(const char *name) { return make_class_object(name); }

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
    const char *name = (const char *)(args != NULL ? args[0] : NULL);
    if (name == NULL) { frame->result = NULL; frame->has_result = 1; return; }
    /* Class.forName loads the class (from a directory or a jar of NABU_CLASSPATH); one that cannot be found still gets its
     * Class object, as the natives of the JDK classes have no class file. */
    char internal[256];
    snprintf(internal, sizeof(internal), "%s", name);
    for (char *p = internal; *p; p++) if (*p == '.') *p = '/';
    nabu_classfile_load_class(internal);
    if (nargs > 1 && args[1] != NULL) nabu_interp_ensure_clinit(internal);   /* forName(name, true, loader) runs the static initializer */
    frame->result = make_class_object(internal);
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
        char *base = strdup(dot ? dot + 1 : s);
        char *dollar = strrchr(base, '$');   /* a nested class: the name after the last $; an anonymous class has none */
        if (dollar) {
            char *rest = dollar + 1;
            int digits = *rest != 0;
            for (char *q = rest; *q; q++) if (*q < '0' || *q > '9') digits = 0;
            char *name = strdup(digits ? "" : rest);
            free(base);
            base = name;
        }
        free(s);
        frame->result = base;
    }
}

int nabu_is_proxy(void *p);

static void native_object_getClass(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    void *obj = (args != NULL) ? args[0] : NULL;
    const char *tn = obj ? nabu_compiled_type_name(obj) : NULL;
    if (obj != NULL && nabu_is_proxy(obj)) tn = "jdk/proxy1/$Proxy0";
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

/* ---- Class hierarchy (superclass and interfaces), for getMethod/getField ---- */
typedef struct { char *name; char *super_name; char **ifaces; int iface_count; } nabu_hier_t;
static nabu_hier_t **hier = NULL;   /* pointers: a registration while a caller holds an entry must not move it */
static int hier_count = 0, hier_cap = 0;

static void hier_register(const char *name, const char *super_name, char **ifaces, int iface_count) {
    if (hier_count >= hier_cap) {
        int nc = hier_cap == 0 ? 64 : hier_cap * 2;
        nabu_hier_t **a = (nabu_hier_t **)realloc(hier, sizeof(nabu_hier_t *) * nc);
        if (a == NULL) return;
        hier = a; hier_cap = nc;
    }
    nabu_hier_t *e = (nabu_hier_t *)calloc(1, sizeof(nabu_hier_t));
    if (e == NULL) return;
    e->name = strdup(name);
    e->super_name = super_name ? strdup(super_name) : NULL;
    e->iface_count = iface_count;
    e->ifaces = (char **)calloc(iface_count > 0 ? iface_count : 1, sizeof(char *));
    for (int i = 0; i < iface_count; i++) e->ifaces[i] = (ifaces && ifaces[i]) ? strdup(ifaces[i]) : NULL;
    hier[hier_count++] = e;
}

static nabu_hier_t *hier_find(const char *name) {
    for (int i = 0; i < hier_count; i++) if (strcmp(hier[i]->name, name) == 0) return hier[i];
    return NULL;
}

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

/* ---- Reflection: descriptors to Class objects ---- */

/* The Class object of the type at *pp in a descriptor; advances *pp past it. Arrays are named as Class.getName does. */
static void *desc_to_class(const char **pp) {
    const char *p = *pp;
    char buf[300];
    const char *prim = NULL;
    switch (*p) {
        case 'I': prim = "int"; break;
        case 'J': prim = "long"; break;
        case 'Z': prim = "boolean"; break;
        case 'B': prim = "byte"; break;
        case 'S': prim = "short"; break;
        case 'C': prim = "char"; break;
        case 'F': prim = "float"; break;
        case 'D': prim = "double"; break;
        case 'V': prim = "void"; break;
        default: break;
    }
    if (prim != NULL) { *pp = p + 1; return make_class_object(prim); }
    const char *start = p;
    while (*p == '[') p++;
    if (*p == 'L') { while (*p != ';' && *p != 0) p++; if (*p == ';') p++; } else if (*p) p++;
    size_t n = (size_t)(p - start);
    if (n >= sizeof(buf)) n = sizeof(buf) - 1;
    if (*start == '[') {
        memcpy(buf, start, n); buf[n] = 0;
    } else {
        /* Lpkg/Name; -> pkg.Name */
        memcpy(buf, start + 1, n - 2); buf[n - 2] = 0;
    }
    for (char *q = buf; *q; q++) if (*q == '/') *q = '.';
    *pp = p;
    return make_class_object(buf);
}

static void *new_object_array(int n) {
    void *arr = calloc(1, 8 + (size_t)(n > 0 ? n : 1) * sizeof(void *));
    if (arr != NULL) *(int *)arr = n;
    return arr;
}

/* Class[] of the parameter types of a method descriptor. */
static void *desc_param_classes(const char *desc) {
    int n = 0;
    const char *p = desc ? strchr(desc, '(') : NULL;
    if (p == NULL) return new_object_array(0);
    p++;
    const char *q = p;
    while (*q != ')' && *q != 0) { while (*q == '[') q++; if (*q == 'L') { while (*q != ';' && *q) q++; } if (*q) q++; n++; }
    void *arr = new_object_array(n);
    void **elems = (void **)((char *)arr + 8);
    for (int i = 0; i < n; i++) elems[i] = desc_to_class(&p);
    return arr;
}

static void *desc_return_class(const char *desc) {
    const char *p = desc ? strchr(desc, ')') : NULL;
    if (p == NULL) return NULL;
    p++;
    return desc_to_class(&p);
}

/* Fill the type fields of a Method or Constructor object that the JDK classes read (getParameterTypes, ...). */
static void fill_executable(void **flds, const char *cls, const char *desc, int access, int is_method) {
    int fi_params = nabu_interp_field_index(cls, "parameterTypes");
    int fi_exc = nabu_interp_field_index(cls, "exceptionTypes");
    int fi_mod = nabu_interp_field_index(cls, "modifiers");
    if (fi_params >= 0) flds[fi_params] = desc_param_classes(desc);
    if (fi_exc >= 0) flds[fi_exc] = new_object_array(0);
    if (fi_mod >= 0) flds[fi_mod] = (void *)(intptr_t)(access & 0xffff);
    if (is_method) {
        int fi_ret = nabu_interp_field_index(cls, "returnType");
        if (fi_ret >= 0) flds[fi_ret] = desc_return_class(desc);
    }
}

static void native_class_getDeclaredMethods(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    const char *clsname = class_handle_name(args != NULL ? args[0] : NULL);
    if (clsname == NULL) { frame->result = NULL; frame->has_result = 1; return; }
    char internal[256];
    snprintf(internal, sizeof(internal), "%s", clsname);
    for (char *p = internal; *p; p++) if (*p == '.') *p = '/';
    /* ensure Method.class loaded for field indices */
    nabu_classfile_load(nabu_dev_path("jdkclasses/classes/java/lang/reflect/Method.class"));
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
        if (strcmp(m->name, "<init>") == 0 || strcmp(m->name, "<clinit>") == 0) continue;
        /* allocate Method object */
        void *obj = calloc(1, sizeof(nabu_object) + (size_t)(fc > 0 ? fc : 1) * sizeof(void *));
        if (obj == NULL) continue;
        nabu_type_info *ti = (nabu_type_info *)nabu_register_interpreted_type("java/lang/reflect/Method", "java/lang/Object", 0, NULL, NULL);
        ((nabu_object *)obj)->type = ti;
        void **fields = (void **)((char *)obj + sizeof(nabu_object));
        if (fi_clazz >= 0) fields[fi_clazz] = make_class_object(clsname); /* Class object */
        if (fi_name >= 0) fields[fi_name] = (void *)strdup(m->name);
        if (fi_slot >= 0) fields[fi_slot] = (void *)(intptr_t)0;
        fill_executable(fields, "java/lang/reflect/Method", m->desc, m->access, 1);
        (void)fi_modifiers;
        nabu_register_method_info(obj, m->class_name, m->name, m->desc);
        elems[i++] = obj;
    }
    if (arr != NULL) *(int *)arr = i; /* constructors and the class initializer are not methods */
    frame->result = arr;
    frame->has_result = 1;
}

static void native_class_getDeclaredConstructors(nabu_interp_frame_t *frame, void **args, int nargs);
static void native_class_getDeclaredFields(nabu_interp_frame_t *frame, void **args, int nargs);

/* ---- Reflection by name: getDeclaredMethod/Constructor/Field and the public variants ---- */

static int param_types_match(void *exe, const char *exe_cls, void *wantArr) {
    int fi = nabu_interp_field_index(exe_cls, "parameterTypes");
    int want_n = wantArr != NULL ? *(int *)wantArr : 0;
    void **want = wantArr != NULL ? (void **)((char *)wantArr + 8) : NULL;
    void *have = NULL;
    if (fi >= 0) have = ((void **)((char *)exe + sizeof(nabu_object)))[fi];
    int have_n = have != NULL ? *(int *)have : 0;
    if (have_n != want_n) return 0;
    void **hv = have != NULL ? (void **)((char *)have + 8) : NULL;
    for (int i = 0; i < want_n; i++) {
        const char *a = class_handle_name(hv[i]);
        const char *b = class_handle_name(want[i]);
        if (a == NULL || b == NULL || strcmp(a, b) != 0) return 0;
    }
    return 1;
}

/* The Method, Constructor or Field of a class by name and parameter types, or NULL. kind: 0 method, 1 constructor,
 * 2 field. public_only: the getMethod/getConstructor/getField variants. */
static void *reflect_find(void *clsObj, int kind, const char *name, void *types, int public_only) {
    nabu_interp_frame_t tmp;
    memset(&tmp, 0, sizeof(tmp));
    void *targs[1] = { clsObj };
    if (kind == 0) native_class_getDeclaredMethods(&tmp, targs, 1);
    else if (kind == 1) native_class_getDeclaredConstructors(&tmp, targs, 1);
    else native_class_getDeclaredFields(&tmp, targs, 1);
    void *arr = tmp.result;
    int n = arr != NULL ? *(int *)arr : 0;
    void **elems = arr != NULL ? (void **)((char *)arr + 8) : NULL;
    const char *exe_cls = kind == 0 ? "java/lang/reflect/Method" : kind == 1 ? "java/lang/reflect/Constructor" : "java/lang/reflect/Field";
    int fi_name = nabu_interp_field_index(exe_cls, "name");
    int fi_mod = nabu_interp_field_index(exe_cls, "modifiers");
    for (int i = 0; i < n; i++) {
        void *e = elems[i];
        void **flds = (void **)((char *)e + sizeof(nabu_object));
        if (kind != 1) {
            const char *en = fi_name >= 0 ? (const char *)flds[fi_name] : NULL;
            if (name == NULL || en == NULL || strcmp(en, name) != 0) continue;
        }
        if (kind != 2 && !param_types_match(e, exe_cls, types)) continue;
        if (public_only && fi_mod >= 0 && !(((int)(intptr_t)flds[fi_mod]) & 0x0001)) continue;
        return e;
    }
    return NULL;
}

/* getMethod and getField also see what a class inherits: its superclasses first, then the interfaces. */
static void *reflect_find_inherited(const char *internal, int kind, const char *name, void *types, int depth) {
    if (internal == NULL || depth > 32) return NULL;
    nabu_classfile_load_class(internal);
    nabu_hier_t *h = hier_find(internal);
    if (h == NULL) return NULL;
    char dotted[256];
    if (h->super_name != NULL) {
        snprintf(dotted, sizeof(dotted), "%s", h->super_name);
        for (char *q = dotted; *q; q++) if (*q == '/') *q = '.';
        void *r = reflect_find(make_class_object(dotted), kind, name, types, 1);
        if (r == NULL) r = reflect_find_inherited(h->super_name, kind, name, types, depth + 1);
        if (r != NULL) return r;
    }
    for (int i = 0; i < h->iface_count; i++) {
        if (h->ifaces[i] == NULL) continue;
        snprintf(dotted, sizeof(dotted), "%s", h->ifaces[i]);
        for (char *q = dotted; *q; q++) if (*q == '/') *q = '.';
        void *r = reflect_find(make_class_object(dotted), kind, name, types, 1);
        if (r == NULL) r = reflect_find_inherited(h->ifaces[i], kind, name, types, depth + 1);
        if (r != NULL) return r;
    }
    return NULL;
}

static void reflect_lookup(nabu_interp_frame_t *frame, void **args, int nargs, int kind, int public_only) {
    void *clsObj = args != NULL ? args[0] : NULL;
    const char *name = (kind != 1 && nargs > 1) ? (const char *)args[1] : NULL;
    void *types = NULL;
    if (kind == 0) types = nargs > 2 ? args[2] : NULL;
    else if (kind == 1) types = nargs > 1 ? args[1] : NULL;
    void *e = reflect_find(clsObj, kind, name, types, public_only);
    if (e == NULL && public_only && kind != 1) {
        const char *cn = class_handle_name(clsObj);
        if (cn != NULL) {
            char internal[256];
            snprintf(internal, sizeof(internal), "%s", cn);
            for (char *q = internal; *q; q++) if (*q == '.') *q = '/';
            e = reflect_find_inherited(internal, kind, name, types, 0);
        }
    }
    if (e != NULL) {
        frame->result = e;
        frame->has_result = 1;
        return;
    }
    if (kind == 2) nabu_interp_throw("java/lang/NoSuchFieldException", "java/lang/ReflectiveOperationException");
    else nabu_interp_throw("java/lang/NoSuchMethodException", "java/lang/ReflectiveOperationException");
    frame->result = NULL;
    frame->has_result = 1;
}

static void native_class_getDeclaredMethod(nabu_interp_frame_t *f, void **a, int n) { reflect_lookup(f, a, n, 0, 0); }
static void native_class_getMethod(nabu_interp_frame_t *f, void **a, int n) { reflect_lookup(f, a, n, 0, 1); }
static void native_class_getDeclaredConstructor(nabu_interp_frame_t *f, void **a, int n) { reflect_lookup(f, a, n, 1, 0); }
static void native_class_getConstructor(nabu_interp_frame_t *f, void **a, int n) { reflect_lookup(f, a, n, 1, 1); }
static void native_class_getDeclaredField(nabu_interp_frame_t *f, void **a, int n) { reflect_lookup(f, a, n, 2, 0); }
static void native_class_getField(nabu_interp_frame_t *f, void **a, int n) { reflect_lookup(f, a, n, 2, 1); }

/* Boxing by descriptor character: a boolean or char becomes a tagged box, the other primitives stay raw (see
 * nabu_interpreter.h); unboxing strips the tag from a primitive parameter. */
static void *box_by_desc(char d, void *raw) {
    if (d == 'Z') return nabu_box_bool(raw != NULL);
    if (d == 'C') return nabu_box_char((int)(intptr_t)raw);
    return raw;
}

static void *unbox_by_desc(char d, void *v) {
    return (d == 'L' || d == '[' || d == 0) ? v : nabu_unbox(v);
}

/* The descriptor character of parameter i (0-based) of a method descriptor: a primitive letter, 'L' or '['. */
static char desc_param_char(const char *desc, int i) {
    const char *p = desc ? strchr(desc, '(') : NULL;
    if (p == NULL) return 0;
    p++;
    for (int k = 0; *p && *p != ')'; k++) {
        char c = *p;
        if (k == i) return c;
        while (*p == '[') p++;
        if (*p == 'L') { while (*p && *p != ';') p++; }
        if (*p) p++;
    }
    return 0;
}

static char desc_return_char(const char *desc) {
    const char *p = desc ? strchr(desc, ')') : NULL;
    return p ? p[1] : 0;
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
    nabu_interp_method_t *callee = nabu_interp_find_method(info->cls, info->name, info->desc);
    int isInstance = callee != NULL ? !(callee->access & 0x0008) : (target != NULL);
    int total = isInstance ? pcnt + 1 : pcnt;
    void **cargs = (void **)malloc(sizeof(void *) * (total > 0 ? total : 1));
    int idx = 0;
    if (isInstance) cargs[idx++] = target;
    for (int i = 0; i < pcnt && i < argc; i++) {
        cargs[idx++] = argArray ? unbox_by_desc(desc_param_char(info->desc, i), argArray[i]) : NULL;
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
    frame->result = box_by_desc(desc_return_char(info->desc), r);
    frame->has_result = 1;
}

static void native_class_getDeclaredConstructors(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    const char *clsname = class_handle_name(args != NULL ? args[0] : NULL);
    if (clsname == NULL) { frame->result = NULL; frame->has_result = 1; return; }
    char internal[256];
    snprintf(internal, sizeof(internal), "%s", clsname);
    for (char *p = internal; *p; p++) if (*p == '.') *p = '/';
    nabu_classfile_load(nabu_dev_path("jdkclasses/classes/java/lang/reflect/Constructor.class"));
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
        fill_executable(fields, "java/lang/reflect/Constructor", m->desc, m->access, 0);
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
            for (int i = 0; i < argc; i++) cargs[i + 1] = argArray ? unbox_by_desc(desc_param_char(info->desc, i), argArray[i]) : NULL;
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
    frame->result = (void *)nabu_interp_object_to_string(args != NULL ? args[0] : NULL);
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

static void native_box_bool(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    frame->result = nabu_box_bool(args != NULL && (int)(intptr_t)args[0] != 0);
    frame->has_result = 1;
}

static void native_box_char(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    frame->result = nabu_box_char(args != NULL ? (int)(intptr_t)args[0] : 0);
    frame->has_result = 1;
}

static void native_unbox(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    frame->result = nabu_unbox(args != NULL ? args[0] : NULL);
    frame->has_result = 1;
}

static void native_identity_int(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    frame->result = (args != NULL) ? args[0] : NULL;
    frame->has_result = 1;
}

/* ---- Jar files: the entries of a zip file (stored or deflated), read without a library ---- */

/* An inflater in the manner of puff.c (Mark Adler): small, no tables to build ahead. */
typedef struct {
    const unsigned char *in; size_t in_len, in_pos;
    unsigned char *out; size_t out_len, out_pos;
    unsigned bit_buf; int bit_cnt;
    int failed;
} nabu_infl;

typedef struct { short count[16]; short symbol[288]; } nabu_huff;

static int infl_bits(nabu_infl *s, int need) {
    long val = s->bit_buf;
    while (s->bit_cnt < need) {
        if (s->in_pos >= s->in_len) { s->failed = 1; return 0; }
        val |= (long)s->in[s->in_pos++] << s->bit_cnt;
        s->bit_cnt += 8;
    }
    s->bit_buf = (unsigned)(val >> need);
    s->bit_cnt -= need;
    return (int)(val & ((1L << need) - 1));
}

static int infl_construct(nabu_huff *h, const short *length, int n) {
    short offs[16];
    for (int len = 0; len <= 15; len++) h->count[len] = 0;
    for (int symbol = 0; symbol < n; symbol++) h->count[length[symbol]]++;
    if (h->count[0] == n) return 0;
    int left = 1;
    for (int len = 1; len <= 15; len++) {
        left <<= 1;
        left -= h->count[len];
        if (left < 0) return left;
    }
    offs[1] = 0;
    for (int len = 1; len < 15; len++) offs[len + 1] = (short)(offs[len] + h->count[len]);
    for (int symbol = 0; symbol < n; symbol++) {
        if (length[symbol] != 0) h->symbol[offs[length[symbol]]++] = (short)symbol;
    }
    return left;
}

static int infl_decode(nabu_infl *s, const nabu_huff *h) {
    int code = 0, first = 0, index = 0;
    for (int len = 1; len <= 15; len++) {
        code |= infl_bits(s, 1);
        if (s->failed) return -1;
        int count = h->count[len];
        if (code - count < first) return h->symbol[index + (code - first)];
        index += count;
        first += count;
        first <<= 1;
        code <<= 1;
    }
    return -1;
}

static int infl_codes(nabu_infl *s, const nabu_huff *lencode, const nabu_huff *distcode) {
    static const short lens[29] = {3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115,
                                   131, 163, 195, 227, 258};
    static const short lext[29] = {0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0};
    static const short dists[30] = {1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769, 1025,
                                    1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577};
    static const short dext[30] = {0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13};
    int symbol;
    do {
        symbol = infl_decode(s, lencode);
        if (symbol < 0 || s->failed) return -1;
        if (symbol < 256) {
            if (s->out_pos >= s->out_len) return -1;
            s->out[s->out_pos++] = (unsigned char)symbol;
        } else if (symbol > 256) {
            symbol -= 257;
            if (symbol >= 29) return -1;
            int len = lens[symbol] + infl_bits(s, lext[symbol]);
            symbol = infl_decode(s, distcode);
            if (symbol < 0 || symbol >= 30 || s->failed) return -1;
            size_t dist = (size_t)(dists[symbol] + infl_bits(s, dext[symbol]));
            if (dist > s->out_pos || s->out_pos + (size_t)len > s->out_len) return -1;
            while (len--) { s->out[s->out_pos] = s->out[s->out_pos - dist]; s->out_pos++; }
        }
    } while (symbol != 256);
    return 0;
}

/* Raw deflate data in, exactly out_len bytes out; 0 when it went well. */
static int nabu_inflate(const unsigned char *in, size_t in_len, unsigned char *out, size_t out_len) {
    nabu_infl s;
    memset(&s, 0, sizeof s);
    s.in = in; s.in_len = in_len; s.out = out; s.out_len = out_len;
    int last;
    do {
        last = infl_bits(&s, 1);
        int type = infl_bits(&s, 2);
        if (s.failed) return -1;
        if (type == 0) {
            s.bit_buf = 0; s.bit_cnt = 0;
            if (s.in_pos + 4 > s.in_len) return -1;
            unsigned len = s.in[s.in_pos] | (unsigned)s.in[s.in_pos + 1] << 8;
            s.in_pos += 4;
            if (s.in_pos + len > s.in_len || s.out_pos + len > s.out_len) return -1;
            memcpy(s.out + s.out_pos, s.in + s.in_pos, len);
            s.in_pos += len; s.out_pos += len;
        } else if (type == 1 || type == 2) {
            static nabu_huff lencode, distcode;
            nabu_huff lc, dc;
            short lengths[320];
            if (type == 1) {
                int sym = 0;
                for (; sym < 144; sym++) lengths[sym] = 8;
                for (; sym < 256; sym++) lengths[sym] = 9;
                for (; sym < 280; sym++) lengths[sym] = 7;
                for (; sym < 288; sym++) lengths[sym] = 8;
                infl_construct(&lc, lengths, 288);
                for (sym = 0; sym < 30; sym++) lengths[sym] = 5;
                infl_construct(&dc, lengths, 30);
            } else {
                static const short order[19] = {16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15};
                int nlen = infl_bits(&s, 5) + 257, ndist = infl_bits(&s, 5) + 1, ncode = infl_bits(&s, 4) + 4;
                if (s.failed || nlen > 286 || ndist > 30) return -1;
                int index;
                for (index = 0; index < ncode; index++) lengths[order[index]] = (short)infl_bits(&s, 3);
                for (; index < 19; index++) lengths[order[index]] = 0;
                if (infl_construct(&lc, lengths, 19) != 0) return -1;
                index = 0;
                while (index < nlen + ndist) {
                    int symbol = infl_decode(&s, &lc);
                    if (symbol < 0 || s.failed) return -1;
                    if (symbol < 16) {
                        lengths[index++] = (short)symbol;
                    } else {
                        int len = 0, rep;
                        if (symbol == 16) {
                            if (index == 0) return -1;
                            len = lengths[index - 1];
                            rep = 3 + infl_bits(&s, 2);
                        } else if (symbol == 17) {
                            rep = 3 + infl_bits(&s, 3);
                        } else {
                            rep = 11 + infl_bits(&s, 7);
                        }
                        if (index + rep > nlen + ndist) return -1;
                        while (rep--) lengths[index++] = (short)len;
                    }
                }
                if (lengths[256] == 0) return -1;
                infl_construct(&lc, lengths, nlen);
                infl_construct(&dc, lengths + nlen, ndist);
            }
            (void)lencode; (void)distcode;
            if (infl_codes(&s, &lc, &dc) != 0) return -1;
        } else {
            return -1;
        }
    } while (!last);
    return s.out_pos == s.out_len ? 0 : -1;
}

typedef struct { char *name; unsigned method; unsigned csize, usize, offset; } nabu_zip_entry;
typedef struct { char *path; nabu_zip_entry *entries; int count; int ok; } nabu_zip;
#define NABU_ZIP_CACHE 16
static nabu_zip nabu_zip_cache[NABU_ZIP_CACHE];
static int nabu_zip_cached = 0;

static unsigned zip_u16(const unsigned char *p) { return p[0] | (unsigned)p[1] << 8; }
static unsigned zip_u32(const unsigned char *p) { return zip_u16(p) | (unsigned)zip_u16(p + 2) << 16; }

/* The central directory of a jar (read once), or NULL when it is no zip file. */
static nabu_zip *zip_open(const char *path) {
    for (int i = 0; i < nabu_zip_cached; i++) {
        if (strcmp(nabu_zip_cache[i].path, path) == 0) return nabu_zip_cache[i].ok ? &nabu_zip_cache[i] : NULL;
    }
    if (nabu_zip_cached >= NABU_ZIP_CACHE) return NULL;
    nabu_zip *z = &nabu_zip_cache[nabu_zip_cached++];
    memset(z, 0, sizeof *z);
    z->path = strdup(path);
    FILE *f = fopen(path, "rb");
    if (f == NULL) return NULL;
    fseek(f, 0, SEEK_END);
    long size = ftell(f);
    long tail = size < 65557 ? size : 65557;
    unsigned char *buf = (unsigned char *)malloc((size_t)tail);
    fseek(f, size - tail, SEEK_SET);
    if (buf == NULL || fread(buf, 1, (size_t)tail, f) != (size_t)tail) { fclose(f); free(buf); return NULL; }
    long eocd = -1;
    for (long i = tail - 22; i >= 0; i--) {
        if (buf[i] == 'P' && buf[i + 1] == 'K' && buf[i + 2] == 5 && buf[i + 3] == 6) { eocd = i; break; }
    }
    if (eocd < 0) { fclose(f); free(buf); return NULL; }
    int count = (int)zip_u16(buf + eocd + 10);
    unsigned cd_size = zip_u32(buf + eocd + 12), cd_offset = zip_u32(buf + eocd + 16);
    free(buf);
    unsigned char *cd = (unsigned char *)malloc(cd_size);
    fseek(f, (long)cd_offset, SEEK_SET);
    if (cd == NULL || fread(cd, 1, cd_size, f) != cd_size) { fclose(f); free(cd); return NULL; }
    fclose(f);
    z->entries = (nabu_zip_entry *)calloc((size_t)(count > 0 ? count : 1), sizeof(nabu_zip_entry));
    size_t at = 0;
    for (int i = 0; i < count && at + 46 <= cd_size; i++) {
        if (zip_u32(cd + at) != 0x02014b50u) break;
        unsigned name_len = zip_u16(cd + at + 28), extra_len = zip_u16(cd + at + 30), comment_len = zip_u16(cd + at + 32);
        nabu_zip_entry *e = &z->entries[z->count++];
        e->method = zip_u16(cd + at + 10);
        e->csize = zip_u32(cd + at + 20);
        e->usize = zip_u32(cd + at + 24);
        e->offset = zip_u32(cd + at + 42);
        e->name = (char *)malloc(name_len + 1);
        memcpy(e->name, cd + at + 46, name_len);
        e->name[name_len] = 0;
        at += 46 + name_len + extra_len + comment_len;
    }
    free(cd);
    z->ok = 1;
    return z;
}

/* The bytes of an entry of a jar (a NUL is added behind them, for text), or NULL; *len is their number. */
static unsigned char *zip_read(const char *jar, const char *entry, size_t *len) {
    nabu_zip *z = zip_open(jar);
    if (z == NULL) return NULL;
    for (int i = 0; i < z->count; i++) {
        nabu_zip_entry *e = &z->entries[i];
        if (strcmp(e->name, entry) != 0) continue;
        FILE *f = fopen(jar, "rb");
        if (f == NULL) return NULL;
        unsigned char local[30];
        fseek(f, (long)e->offset, SEEK_SET);
        if (fread(local, 1, 30, f) != 30 || zip_u32(local) != 0x04034b50u) { fclose(f); return NULL; }
        fseek(f, (long)(e->offset + 30 + zip_u16(local + 26) + zip_u16(local + 28)), SEEK_SET);
        unsigned char *packed = (unsigned char *)malloc(e->csize > 0 ? e->csize : 1);
        unsigned char *out = (unsigned char *)malloc((size_t)e->usize + 1);
        int ok = packed != NULL && out != NULL && fread(packed, 1, e->csize, f) == e->csize;
        fclose(f);
        if (ok && e->method == 0 && e->csize == e->usize) memcpy(out, packed, e->usize);
        else if (ok && e->method == 8) ok = nabu_inflate(packed, e->csize, out, e->usize) == 0;
        else ok = 0;
        free(packed);
        if (!ok) { free(out); return NULL; }
        out[e->usize] = 0;
        if (len != NULL) *len = e->usize;
        return out;
    }
    return NULL;
}

/* Whether a classpath entry is a jar (a file, not a directory). */
static int is_jar_path(const char *path) {
    size_t n = strlen(path);
    return n > 4 && (strcmp(path + n - 4, ".jar") == 0 || strcmp(path + n - 4, ".zip") == 0);
}

#ifdef _WIN32
#define NABU_CP_SEP ";"
#else
#define NABU_CP_SEP ":"
#endif

/* The providers of a service: "iface=impl" lines of $NABU_DEV_DIR/services.txt, and the META-INF/services/<iface> files
 * in the directories of NABU_CLASSPATH. impls holds up to 64 names of 256 characters. */
static int collect_service_impls(const char *iface, char impls[64][256]) {
    int cnt = 0;
    if (iface == NULL) return 0;
    FILE *f = fopen(nabu_dev_path("services.txt"), "r");
    if (f) {
        char line[512];
        while (fgets(line, sizeof(line), f) && cnt < 64) {
            char *nl = strchr(line, '\n'); if (nl) *nl = 0;
            char *eq = strchr(line, '=');
            if (!eq) continue;
            *eq = 0;
            if (strcmp(line, iface) == 0) {
                snprintf(impls[cnt], 256, "%s", eq + 1);
                cnt++;
            }
        }
        fclose(f);
    }
    const char *cp = getenv("NABU_CLASSPATH");
    if (cp != NULL && *cp) {
        char dotted[256];
        snprintf(dotted, sizeof(dotted), "%s", iface);
        for (char *q = dotted; *q; q++) if (*q == '/') *q = '.';
        char *copy = strdup(cp);
        char *save = NULL;
        for (char *dir = strtok_r(copy, NABU_CP_SEP, &save); dir != NULL && cnt < 64; dir = strtok_r(NULL, NABU_CP_SEP, &save)) {
            char path[1024];
            unsigned char *text = NULL;
            if (is_jar_path(dir)) {
                /* a jar: the service file is an entry of it */
                snprintf(path, sizeof(path), "META-INF/services/%s", dotted);
                text = zip_read(dir, path, NULL);
            } else {
                snprintf(path, sizeof(path), "%s/META-INF/services/%s", dir, dotted);
                FILE *sf = fopen(path, "rb");
                if (sf != NULL) {
                    fseek(sf, 0, SEEK_END);
                    long size = ftell(sf);
                    fseek(sf, 0, SEEK_SET);
                    text = (unsigned char *)malloc((size_t)size + 1);
                    if (text != NULL) {
                        size_t got = fread(text, 1, (size_t)size, sf);
                        text[got] = 0;
                    }
                    fclose(sf);
                }
            }
            if (text == NULL) continue;
            /* one provider per line; '#' starts a comment */
            char *line_save = NULL;
            for (char *line = strtok_r((char *)text, "\n", &line_save); line != NULL && cnt < 64;
                 line = strtok_r(NULL, "\n", &line_save)) {
                char *hash = strchr(line, '#'); if (hash) *hash = 0;
                char *b = line;
                while (*b == ' ' || *b == '\t') b++;
                size_t n = strlen(b);
                while (n > 0 && (b[n - 1] == '\r' || b[n - 1] == ' ' || b[n - 1] == '\t')) b[--n] = 0;
                if (n == 0 || n >= 256) continue;
                int dup = 0;
                for (int i = 0; i < cnt; i++) if (strcmp(impls[i], b) == 0) dup = 1;
                if (!dup) { snprintf(impls[cnt], 256, "%s", b); cnt++; }
            }
            free(text);
        }
        free(copy);
    }
    return cnt;
}

static void native_reflect_loadServices(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)nargs;
    const char *iface = (const char *)(args != NULL ? args[0] : NULL);
    char impls[64][256];
    memset(impls, 0, sizeof(impls));
    int cnt = collect_service_impls(iface, impls);

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
    nabu_classfile_load(nabu_dev_path("jdkclasses/classes/java/lang/reflect/Field.class"));
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
    int fi_type = nabu_interp_field_index("java/lang/reflect/Field", "type");
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
        if (fi_modifiers >= 0) flds[fi_modifiers] = (void *)(intptr_t)(fields[i].access & 0xffff);
        if (fi_type >= 0 && fields[i].desc != NULL) { const char *dp = fields[i].desc; flds[fi_type] = desc_to_class(&dp); }
        nabu_register_field_info(obj, internal, fields[i].name, fields[i].index);
        elems[idx++] = obj;
    }
    /* fix count */
    if (arr != NULL) *(int *)arr = idx;
    frame->result = arr;
    frame->has_result = 1;
}

/* The first character of the descriptor of a field (a primitive letter, 'L' or '['), 0 when unknown. */
static char field_desc_char(const char *cls, const char *name) {
    for (int i = 0; i < field_count_g; i++) {
        if (strcmp(fields[i].class_name, cls) == 0 && strcmp(fields[i].name, name) == 0)
            return fields[i].desc != NULL ? fields[i].desc[0] : 0;
    }
    return 0;
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
    frame->result = box_by_desc(field_desc_char(info->cls, info->name), val);
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
        fields[info->index] = unbox_by_desc(field_desc_char(info->cls, info->name), value);
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
    /* A proxy object reports a class named $Proxy<n> (see native_object_getClass). */
    const char *name = class_handle_name(args != NULL ? args[0] : NULL);
    frame->result = (void *)(intptr_t)((name != NULL && strstr(name, "$Proxy") != NULL) ? 1 : 0);
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
    /* args: src, srcPos, dest, destPos, length. The element size is next to the length of the array (0 means a pointer). */
    void *src = (args != NULL) ? args[0] : NULL;
    int srcPos = (nargs > 1) ? (int)(intptr_t)args[1] : 0;
    void *dest = (nargs > 2) ? args[2] : NULL;
    int destPos = (nargs > 3) ? (int)(intptr_t)args[3] : 0;
    int length = (nargs > 4) ? (int)(intptr_t)args[4] : 0;
    if (src && dest && length > 0) {
        int esize = ((int *)src)[1];
        if (esize <= 0) esize = ((int *)dest)[1] > 0 ? ((int *)dest)[1] : (int)sizeof(void *);
        if (srcPos < 0 || destPos < 0 || srcPos + length > *(int *)src || destPos + length > *(int *)dest) {
            nabu_interp_throw("java/lang/ArrayIndexOutOfBoundsException", "java/lang/RuntimeException");
        } else {
            memmove((char *)dest + 8 + (size_t)destPos * esize, (char *)src + 8 + (size_t)srcPos * esize, (size_t)length * esize);
        }
    }
    frame->has_result = 0;
}

static void native_object_hashCode(nabu_interp_frame_t *frame, void **args, int nargs) {
    frame->result = (void *)(intptr_t)nabu_interp_hash_code(nargs > 0 ? args[0] : NULL);
    frame->has_result = 1;
}

static int numbers_equal(void *a, void *b);
static void native_object_equals(nabu_interp_frame_t *frame, void **args, int nargs) {
    void *a = (args != NULL) ? args[0] : NULL;
    void *b = (nargs > 1) ? args[1] : NULL;
    int r = (a == b) ? 1 : 0;
    if (!r && a && b) {
        if (numbers_equal(a, b)) r = 1;
        else if ((uintptr_t)a >= 0x100000000ull && (uintptr_t)a < 0x800000000000ull && (uintptr_t)b >= 0x100000000ull && (uintptr_t)b < 0x800000000000ull
                 && nabu_compiled_type_name(a) == NULL && nabu_compiled_type_name(b) == NULL) {
            r = strcmp((const char *)a, (const char *)b) == 0;   /* two Strings */
        }
    }
    frame->result = (void *)(intptr_t)r;
    frame->has_result = 1;
}

static void native_system_currentTimeMillis(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)args; (void)nargs;
#ifdef _WIN32
    FILETIME ft; GetSystemTimeAsFileTime(&ft);
    long long t = (((long long)ft.dwHighDateTime << 32) | ft.dwLowDateTime) / 10000LL - 11644473600000LL;
    frame->result = (void *)(intptr_t)t;
#else
    struct timeval tv;
    gettimeofday(&tv, NULL);
    frame->result = (void *)(intptr_t)(tv.tv_sec * 1000LL + tv.tv_usec / 1000);
#endif
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
    int cnt = collect_service_impls(iface, impls);

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

/* Iterator.hasNext/next are natives for the iterator of a ServiceLoader; any other iterator runs its own method. */
static int sl_delegate(nabu_interp_frame_t *frame, void **args, int nargs, const char *name, const char *desc) {
    void *h = (args != NULL && nargs > 0) ? args[0] : NULL;
    if (h == NULL || sl_find(h) != NULL) return 0;
    const char *rt = ((uintptr_t)h >= 0x100000000ull && (uintptr_t)h < 0x800000000000ull) ? nabu_compiled_type_name(h) : NULL;
    nabu_interp_method_t *t = rt != NULL ? nabu_interp_resolve_in_hierarchy(rt, name, desc, 0) : NULL;
    if (t == NULL || t->code == NULL) return 0;
    frame->result = nabu_interp_invoke(t, args, nargs);
    frame->has_result = 1;
    return 1;
}

static void native_sl_hasNext(nabu_interp_frame_t *frame, void **args, int nargs) {
    if (sl_delegate(frame, args, nargs, "hasNext", "()Z")) return;
    void *h = (args != NULL) ? args[0] : NULL;
    nabu_sl_state_t *s = sl_find(h);
    int r = (s && s->idx < s->count) ? 1 : 0;
    frame->result = (void *)(intptr_t)r;
    frame->has_result = 1;
}

static void native_sl_next(nabu_interp_frame_t *frame, void **args, int nargs) {
    if (sl_delegate(frame, args, nargs, "next", "()Ljava/lang/Object;")) return;
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
        *(int *)arr = newLen; ((int *)arr)[1] = 4;
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
    snprintf(buf, sizeof(buf), "%s/jtest2/%s.class", nabu_dev_dir(), tmp);
    FILE *f = fopen(buf, "rb");
    if (f) { fclose(f); return strdup(buf); }
    snprintf(buf, sizeof(buf), "%s/jtest/%s.class", nabu_dev_dir(), tmp);
    f = fopen(buf, "rb");
    if (f) { fclose(f); return strdup(buf); }
    snprintf(buf, sizeof(buf), "%s/jtest2/%s.class", nabu_dev_dir(), tmp);
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

/* ---- StringBuilder.append of the other types, Character, Math on doubles and more String methods ---- */

static void sb_append_text(nabu_interp_frame_t *frame, void *obj, char *text) {
    void *a[2] = { obj, (void *)text };
    native_sb_append_str(frame, a, 2);
}

static void native_sb_append_char(nabu_interp_frame_t *frame, void **args, int nargs) {
    void *obj = (args != NULL) ? args[0] : NULL;
    unsigned c = (nargs > 1) ? (unsigned)(intptr_t)args[1] & 0xffff : 0;
    char tmp[8];
    if (c < 0x80) { tmp[0] = (char)c; tmp[1] = 0; }
    else if (c < 0x800) { tmp[0] = (char)(0xC0 | (c >> 6)); tmp[1] = (char)(0x80 | (c & 0x3F)); tmp[2] = 0; }
    else { tmp[0] = (char)(0xE0 | (c >> 12)); tmp[1] = (char)(0x80 | ((c >> 6) & 0x3F)); tmp[2] = (char)(0x80 | (c & 0x3F)); tmp[3] = 0; }
    sb_append_text(frame, obj, strdup(tmp));
}

static void native_sb_append_long(nabu_interp_frame_t *frame, void **args, int nargs) {
    void *obj = (args != NULL) ? args[0] : NULL;
    long v = (nargs > 1) ? (long)(intptr_t)args[1] : 0;
    char tmp[32]; snprintf(tmp, sizeof tmp, "%ld", v);
    sb_append_text(frame, obj, strdup(tmp));
}

static void native_sb_append_bool(nabu_interp_frame_t *frame, void **args, int nargs) {
    void *obj = (args != NULL) ? args[0] : NULL;
    int v = (nargs > 1) ? (int)(intptr_t)args[1] : 0;
    sb_append_text(frame, obj, strdup(v ? "true" : "false"));
}

static void native_sb_append_double(nabu_interp_frame_t *frame, void **args, int nargs) {
    void *obj = (args != NULL) ? args[0] : NULL;
    long bits = (nargs > 1) ? (long)(intptr_t)args[1] : 0;
    double v; memcpy(&v, &bits, sizeof v);
    sb_append_text(frame, obj, nabu_interp_double_to_string(v));
}

static void native_sb_append_float(nabu_interp_frame_t *frame, void **args, int nargs) {
    void *obj = (args != NULL) ? args[0] : NULL;
    int bits = (nargs > 1) ? (int)(intptr_t)args[1] : 0;
    float v; memcpy(&v, &bits, sizeof v);
    sb_append_text(frame, obj, nabu_interp_float_to_string(v));
}

static void native_sb_append_obj(nabu_interp_frame_t *frame, void **args, int nargs) {
    void *obj = (args != NULL) ? args[0] : NULL;
    sb_append_text(frame, obj, nabu_interp_object_to_string(nargs > 1 ? args[1] : NULL));
}

/* The text of a StringBuilder (NULL when the pointer is none). */
char *nabu_interp_sb_text(void *obj) { return sb_get(obj); }

#define NATIVE_RET_INT(frame, v) do { (frame)->result = (void *)(intptr_t)(v); (frame)->has_result = 1; } while (0)

static void native_char_isDigit(nabu_interp_frame_t *frame, void **args, int nargs) { int c = nargs > 0 ? (int)(intptr_t)args[0] : 0; NATIVE_RET_INT(frame, c >= '0' && c <= '9'); }
static void native_char_isLetter(nabu_interp_frame_t *frame, void **args, int nargs) { int c = nargs > 0 ? (int)(intptr_t)args[0] : 0; NATIVE_RET_INT(frame, (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c >= 0xC0); }
static void native_char_isLetterOrDigit(nabu_interp_frame_t *frame, void **args, int nargs) { int c = nargs > 0 ? (int)(intptr_t)args[0] : 0; NATIVE_RET_INT(frame, (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c >= 0xC0); }
static void native_char_isUpperCase(nabu_interp_frame_t *frame, void **args, int nargs) { int c = nargs > 0 ? (int)(intptr_t)args[0] : 0; NATIVE_RET_INT(frame, c >= 'A' && c <= 'Z'); }
static void native_char_isLowerCase(nabu_interp_frame_t *frame, void **args, int nargs) { int c = nargs > 0 ? (int)(intptr_t)args[0] : 0; NATIVE_RET_INT(frame, c >= 'a' && c <= 'z'); }
static void native_char_isWhitespace(nabu_interp_frame_t *frame, void **args, int nargs) { int c = nargs > 0 ? (int)(intptr_t)args[0] : 0; NATIVE_RET_INT(frame, c == ' ' || (c >= 9 && c <= 13) || (c >= 0x1C && c <= 0x1F)); }
static void native_char_toUpperCase(nabu_interp_frame_t *frame, void **args, int nargs) { int c = nargs > 0 ? (int)(intptr_t)args[0] : 0; NATIVE_RET_INT(frame, (c >= 'a' && c <= 'z') ? c - 32 : c); }
static void native_char_toLowerCase(nabu_interp_frame_t *frame, void **args, int nargs) { int c = nargs > 0 ? (int)(intptr_t)args[0] : 0; NATIVE_RET_INT(frame, (c >= 'A' && c <= 'Z') ? c + 32 : c); }

static void native_char_digit(nabu_interp_frame_t *frame, void **args, int nargs) {
    int c = nargs > 0 ? (int)(intptr_t)args[0] : 0, radix = nargs > 1 ? (int)(intptr_t)args[1] : 10, d = -1;
    if (c >= '0' && c <= '9') d = c - '0'; else if (c >= 'a' && c <= 'z') d = c - 'a' + 10; else if (c >= 'A' && c <= 'Z') d = c - 'A' + 10;
    NATIVE_RET_INT(frame, (d >= 0 && d < radix && radix >= 2 && radix <= 36) ? d : -1);
}
static void native_char_forDigit(nabu_interp_frame_t *frame, void **args, int nargs) {
    int d = nargs > 0 ? (int)(intptr_t)args[0] : 0, radix = nargs > 1 ? (int)(intptr_t)args[1] : 10;
    NATIVE_RET_INT(frame, (d >= 0 && d < radix && radix >= 2 && radix <= 36) ? (d < 10 ? '0' + d : 'a' + d - 10) : 0);
}

static double arg_double(void **args, int i, int nargs) { long bits = (i < nargs) ? (long)(intptr_t)args[i] : 0; double v; memcpy(&v, &bits, sizeof v); return v; }
static float arg_float(void **args, int i, int nargs) { int bits = (i < nargs) ? (int)(intptr_t)args[i] : 0; float v; memcpy(&v, &bits, sizeof v); return v; }
static void ret_double(nabu_interp_frame_t *frame, double v) { long bits; memcpy(&bits, &v, sizeof bits); frame->result = (void *)(intptr_t)bits; frame->has_result = 1; }
static void ret_float(nabu_interp_frame_t *frame, float v) { int bits; memcpy(&bits, &v, sizeof bits); frame->result = (void *)(intptr_t)bits; frame->has_result = 1; }

static void native_math_abs_d(nabu_interp_frame_t *frame, void **args, int nargs) { ret_double(frame, fabs(arg_double(args, 0, nargs))); }
static void native_math_abs_f(nabu_interp_frame_t *frame, void **args, int nargs) { ret_float(frame, fabsf(arg_float(args, 0, nargs))); }
static void native_math_max_d(nabu_interp_frame_t *frame, void **args, int nargs) { double a = arg_double(args, 0, nargs), b = arg_double(args, 1, nargs); ret_double(frame, (a != a || b != b) ? a + b : (a > b ? a : b)); }
static void native_math_min_d(nabu_interp_frame_t *frame, void **args, int nargs) { double a = arg_double(args, 0, nargs), b = arg_double(args, 1, nargs); ret_double(frame, (a != a || b != b) ? a + b : (a < b ? a : b)); }
static void native_math_max_f(nabu_interp_frame_t *frame, void **args, int nargs) { float a = arg_float(args, 0, nargs), b = arg_float(args, 1, nargs); ret_float(frame, (a != a || b != b) ? a + b : (a > b ? a : b)); }
static void native_math_min_f(nabu_interp_frame_t *frame, void **args, int nargs) { float a = arg_float(args, 0, nargs), b = arg_float(args, 1, nargs); ret_float(frame, (a != a || b != b) ? a + b : (a < b ? a : b)); }
static void native_math_max_l(nabu_interp_frame_t *frame, void **args, int nargs) { long a = nargs > 0 ? (long)(intptr_t)args[0] : 0, b = nargs > 1 ? (long)(intptr_t)args[1] : 0; frame->result = (void *)(intptr_t)(a > b ? a : b); frame->has_result = 1; }
static void native_math_min_l(nabu_interp_frame_t *frame, void **args, int nargs) { long a = nargs > 0 ? (long)(intptr_t)args[0] : 0, b = nargs > 1 ? (long)(intptr_t)args[1] : 0; frame->result = (void *)(intptr_t)(a < b ? a : b); frame->has_result = 1; }
static void native_math_sin(nabu_interp_frame_t *frame, void **args, int nargs) { ret_double(frame, sin(arg_double(args, 0, nargs))); }
static void native_math_cos(nabu_interp_frame_t *frame, void **args, int nargs) { ret_double(frame, cos(arg_double(args, 0, nargs))); }
static void native_math_tan(nabu_interp_frame_t *frame, void **args, int nargs) { ret_double(frame, tan(arg_double(args, 0, nargs))); }
static void native_math_exp(nabu_interp_frame_t *frame, void **args, int nargs) { ret_double(frame, exp(arg_double(args, 0, nargs))); }
static void native_math_log(nabu_interp_frame_t *frame, void **args, int nargs) { ret_double(frame, log(arg_double(args, 0, nargs))); }
static void native_math_log10(nabu_interp_frame_t *frame, void **args, int nargs) { ret_double(frame, log10(arg_double(args, 0, nargs))); }
static void native_math_cbrt(nabu_interp_frame_t *frame, void **args, int nargs) { ret_double(frame, cbrt(arg_double(args, 0, nargs))); }
static void native_math_atan2(nabu_interp_frame_t *frame, void **args, int nargs) { ret_double(frame, atan2(arg_double(args, 0, nargs), arg_double(args, 2, nargs))); }
static void native_math_hypot(nabu_interp_frame_t *frame, void **args, int nargs) { ret_double(frame, hypot(arg_double(args, 0, nargs), arg_double(args, 2, nargs))); }

static const char *arg_cstr(void **args, int i, int nargs) { return (i < nargs && args[i] != NULL) ? (const char *)args[i] : ""; }

static void native_string_contains(nabu_interp_frame_t *frame, void **args, int nargs) {
    char *needle = nabu_interp_object_to_string(nargs > 1 ? args[1] : NULL);
    NATIVE_RET_INT(frame, strstr(arg_cstr(args, 0, nargs), needle) != NULL);
    free(needle);
}
static void native_string_lastIndexOf_char(nabu_interp_frame_t *frame, void **args, int nargs) {
    const char *s = arg_cstr(args, 0, nargs);
    const char *p = strrchr(s, nargs > 1 ? (int)(intptr_t)args[1] : 0);
    NATIVE_RET_INT(frame, p != NULL ? (int)(p - s) : -1);
}
static void native_string_lastIndexOf_str(nabu_interp_frame_t *frame, void **args, int nargs) {
    const char *s = arg_cstr(args, 0, nargs), *t = arg_cstr(args, 1, nargs);
    int best = -1;
    size_t tl = strlen(t);
    for (const char *p = s; (p = strstr(p, t)) != NULL; p++) { best = (int)(p - s); if (tl == 0 && !*p) break; }
    NATIVE_RET_INT(frame, best);
}
static void native_string_compareTo(nabu_interp_frame_t *frame, void **args, int nargs) {
    const unsigned char *a = (const unsigned char *)arg_cstr(args, 0, nargs), *b = (const unsigned char *)arg_cstr(args, 1, nargs);
    while (*a && *a == *b) { a++; b++; }
    NATIVE_RET_INT(frame, (int)*a - (int)*b);
}
static void native_string_equalsIgnoreCase(nabu_interp_frame_t *frame, void **args, int nargs) {
    const char *a = arg_cstr(args, 0, nargs), *b = arg_cstr(args, 1, nargs);
    int eq = nargs > 1 && args[1] != NULL;
    for (; eq && (*a || *b); a++, b++) if (tolower((unsigned char)*a) != tolower((unsigned char)*b)) eq = 0;
    NATIVE_RET_INT(frame, eq);
}
static void native_string_repeat(nabu_interp_frame_t *frame, void **args, int nargs) {
    const char *s = arg_cstr(args, 0, nargs);
    int n = nargs > 1 ? (int)(intptr_t)args[1] : 0;
    size_t sl = strlen(s);
    char *r = (char *)malloc(sl * (size_t)(n > 0 ? n : 0) + 1);
    r[0] = 0;
    for (int i = 0; i < n; i++) memcpy(r + sl * (size_t)i, s, sl + 1);
    frame->result = r; frame->has_result = 1;
}
static void native_string_substring_from(nabu_interp_frame_t *frame, void **args, int nargs) {
    const char *s = arg_cstr(args, 0, nargs);
    int from = nargs > 1 ? (int)(intptr_t)args[1] : 0;
    int len = (int)strlen(s);
    frame->result = strdup(from >= 0 && from <= len ? s + from : "");
    frame->has_result = 1;
}
/* String.split for a literal separator ("," or a single escaped character), "\\s+" and "\s*,\s*" are not handled: the JDK's regex is not run here. */
static void native_string_split(nabu_interp_frame_t *frame, void **args, int nargs) {
    const char *s = arg_cstr(args, 0, nargs);
    const char *re = arg_cstr(args, 1, nargs);
    char sep[8] = {0};
    int ws = 0;
    if (strcmp(re, "\\s+") == 0 || strcmp(re, "\\s") == 0 || strcmp(re, " +") == 0) ws = 1;
    else if (re[0] == '\\' && re[1] && !re[2]) { sep[0] = re[1]; }
    else { snprintf(sep, sizeof sep, "%s", re); }
    size_t seplen = strlen(sep);
    char **parts = NULL; int n = 0, cap = 0;
    const char *p = s;
    for (;;) {
        const char *q;
        size_t skip;
        if (ws) { q = p; while (*q && !isspace((unsigned char)*q)) q++; if (!*q) q = NULL; skip = 1; if (q && (strcmp(re, "\\s+") == 0 || strcmp(re, " +") == 0)) { const char *r = q; while (isspace((unsigned char)*r)) r++; skip = (size_t)(r - q); } }
        else { q = seplen > 0 ? strstr(p, sep) : NULL; skip = seplen; }
        size_t len = q ? (size_t)(q - p) : strlen(p);
        if (n >= cap) { cap = cap ? cap * 2 : 8; parts = (char **)realloc(parts, sizeof(char *) * (size_t)cap); }
        parts[n] = (char *)malloc(len + 1); memcpy(parts[n], p, len); parts[n][len] = 0; n++;
        if (!q) break;
        p = q + skip;
    }
    while (n > 1 && parts[n - 1][0] == 0) n--;   /* trailing empty strings go */
    if (n == 1 && parts[0][0] == 0 && s[0] != 0) n = 0;
    void *arr = calloc(1, 8 + (size_t)(n > 0 ? n : 1) * sizeof(void *));
    *(int *)arr = n;
    for (int i = 0; i < n; i++) ((void **)((char *)arr + 8))[i] = parts[i];
    free(parts);
    frame->result = arr; frame->has_result = 1;
}

static void native_sb_length(nabu_interp_frame_t *frame, void **args, int nargs) {
    char *cur = sb_get(nargs > 0 ? args[0] : NULL);
    NATIVE_RET_INT(frame, cur ? (int)strlen(cur) : 0);
}
static void native_sb_charAt(nabu_interp_frame_t *frame, void **args, int nargs) {
    char *cur = sb_get(nargs > 0 ? args[0] : NULL);
    int i = nargs > 1 ? (int)(intptr_t)args[1] : 0;
    NATIVE_RET_INT(frame, (cur && i >= 0 && i < (int)strlen(cur)) ? (unsigned char)cur[i] : 0);
}
static void native_sb_setLength(nabu_interp_frame_t *frame, void **args, int nargs) {
    void *obj = nargs > 0 ? args[0] : NULL;
    char *cur = sb_get(obj);
    int n = nargs > 1 ? (int)(intptr_t)args[1] : 0;
    int len = cur ? (int)strlen(cur) : 0;
    char *t = (char *)malloc((size_t)(n > len ? n : len) + 1);
    if (cur) memcpy(t, cur, (size_t)len);
    for (int i = len; i < n; i++) t[i] = 0;
    t[n > 0 ? n : 0] = 0;
    sb_set(obj, n > len ? (t[len] = 0, t) : t);
    free(t);
    frame->has_result = 0;
}
static void native_sb_deleteCharAt(nabu_interp_frame_t *frame, void **args, int nargs) {
    void *obj = nargs > 0 ? args[0] : NULL;
    char *cur = sb_get(obj);
    int i = nargs > 1 ? (int)(intptr_t)args[1] : 0;
    if (cur && i >= 0 && i < (int)strlen(cur)) { char *t = strdup(cur); memmove(t + i, t + i + 1, strlen(t + i + 1) + 1); sb_set(obj, t); free(t); }
    frame->result = obj; frame->has_result = 1;
}

/* ---- jdk.internal.misc.Unsafe, CDS and friends over the objects of the interpreter ----
 * An object is its header followed by one 8-byte slot per field; an array is its length (8 bytes) followed by the elements.
 * A field offset is therefore the byte offset in the object, and arrayBaseOffset is 8. */

#define UNSAFE_ADDR(args, nargs) ((char *)((nargs > 1) ? args[1] : NULL) + ((nargs > 2) ? (long)(intptr_t)args[2] : 0))

static int numbers_equal(void *a, void *b);
static void native_unsafe_getUnsafe(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)args; (void)nargs;
    static void *theUnsafe = NULL;
    if (theUnsafe == NULL) theUnsafe = nabu_interp_new_object("jdk/internal/misc/Unsafe");
    frame->result = theUnsafe; frame->has_result = 1;
}

static void native_unsafe_objectFieldOffset(nabu_interp_frame_t *frame, void **args, int nargs) {
    const char *cls = (nargs > 1) ? class_handle_name(args[1]) : NULL;
    const char *name = (nargs > 2 && args[2] != NULL) ? (const char *)args[2] : NULL;
    int fi = (cls != NULL && name != NULL) ? nabu_interp_field_index(cls, name) : -1;
    frame->result = (void *)(intptr_t)(fi >= 0 ? (long)(sizeof(nabu_object) + (size_t)fi * sizeof(void *)) : -1L);
    frame->has_result = 1;
}

static void native_unsafe_objectFieldOffsetField(nabu_interp_frame_t *frame, void **args, int nargs) {
    nabu_field_info_t *fi = (nargs > 1) ? nabu_lookup_field_info(args[1]) : NULL;
    int idx = fi != NULL ? nabu_interp_field_index(fi->cls, fi->name) : -1;
    frame->result = (void *)(intptr_t)(idx >= 0 ? (long)(sizeof(nabu_object) + (size_t)idx * sizeof(void *)) : -1L);
    frame->has_result = 1;
}

static void native_unsafe_arrayBaseOffset(nabu_interp_frame_t *frame, void **args, int nargs) { (void)args; (void)nargs; NATIVE_RET_INT(frame, 8); }

static void native_unsafe_arrayIndexScale(nabu_interp_frame_t *frame, void **args, int nargs) {
    const char *cls = (nargs > 1) ? class_handle_name(args[1]) : NULL;
    int scale = 8;
    if (cls != NULL && cls[0] == '[') {
        switch (cls[1]) { case 'B': case 'Z': scale = 1; break; case 'S': case 'C': scale = 2; break; case 'I': case 'F': scale = 4; break; default: scale = 8; }
    }
    NATIVE_RET_INT(frame, scale);
}

static void native_unsafe_arrayBaseOffsetL(nabu_interp_frame_t *frame, void **args, int nargs) { (void)args; (void)nargs; frame->result = (void *)(intptr_t)8; frame->has_result = 1; }
static void native_unsafe_arrayIndexScaleL(nabu_interp_frame_t *frame, void **args, int nargs) { native_unsafe_arrayIndexScale(frame, args, nargs); }
static void native_unsafe_getInt(nabu_interp_frame_t *frame, void **args, int nargs) { NATIVE_RET_INT(frame, *(int32_t *)UNSAFE_ADDR(args, nargs)); }
static void native_unsafe_putInt(nabu_interp_frame_t *frame, void **args, int nargs) { *(int32_t *)UNSAFE_ADDR(args, nargs) = (nargs > 3) ? (int32_t)(intptr_t)args[3] : 0; frame->has_result = 0; }
static void native_unsafe_getLong(nabu_interp_frame_t *frame, void **args, int nargs) { frame->result = (void *)(intptr_t)*(int64_t *)UNSAFE_ADDR(args, nargs); frame->has_result = 1; }
static void native_unsafe_putLong(nabu_interp_frame_t *frame, void **args, int nargs) { *(int64_t *)UNSAFE_ADDR(args, nargs) = (nargs > 3) ? (int64_t)(intptr_t)args[3] : 0; frame->has_result = 0; }
static void native_unsafe_getRef(nabu_interp_frame_t *frame, void **args, int nargs) { frame->result = *(void **)UNSAFE_ADDR(args, nargs); frame->has_result = 1; }
static void native_unsafe_putRef(nabu_interp_frame_t *frame, void **args, int nargs) { *(void **)UNSAFE_ADDR(args, nargs) = (nargs > 3) ? args[3] : NULL; frame->has_result = 0; }
static void native_unsafe_getByte(nabu_interp_frame_t *frame, void **args, int nargs) { NATIVE_RET_INT(frame, *(int8_t *)UNSAFE_ADDR(args, nargs)); }
static void native_unsafe_putByte(nabu_interp_frame_t *frame, void **args, int nargs) { *(int8_t *)UNSAFE_ADDR(args, nargs) = (nargs > 3) ? (int8_t)(intptr_t)args[3] : 0; frame->has_result = 0; }

static void native_unsafe_casInt(nabu_interp_frame_t *frame, void **args, int nargs) {
    int32_t *p = (int32_t *)UNSAFE_ADDR(args, nargs);
    int32_t expect = (nargs > 3) ? (int32_t)(intptr_t)args[3] : 0, update = (nargs > 4) ? (int32_t)(intptr_t)args[4] : 0;
    NATIVE_RET_INT(frame, __sync_bool_compare_and_swap(p, expect, update));
}
static void native_unsafe_casLong(nabu_interp_frame_t *frame, void **args, int nargs) {
    int64_t *p = (int64_t *)UNSAFE_ADDR(args, nargs);
    int64_t expect = (nargs > 3) ? (int64_t)(intptr_t)args[3] : 0, update = (nargs > 4) ? (int64_t)(intptr_t)args[4] : 0;
    NATIVE_RET_INT(frame, __sync_bool_compare_and_swap(p, expect, update));
}
static void native_unsafe_casRef(nabu_interp_frame_t *frame, void **args, int nargs) {
    void **p = (void **)UNSAFE_ADDR(args, nargs);
    void *expect = (nargs > 3) ? args[3] : NULL, *update = (nargs > 4) ? args[4] : NULL;
    NATIVE_RET_INT(frame, __sync_bool_compare_and_swap(p, expect, update));
}
static void native_unsafe_witnessInt(nabu_interp_frame_t *frame, void **args, int nargs) {   /* compareAndExchangeInt */
    int32_t *p = (int32_t *)UNSAFE_ADDR(args, nargs);
    int32_t expect = (nargs > 3) ? (int32_t)(intptr_t)args[3] : 0, update = (nargs > 4) ? (int32_t)(intptr_t)args[4] : 0;
    NATIVE_RET_INT(frame, __sync_val_compare_and_swap(p, expect, update));
}
static void native_unsafe_witnessLong(nabu_interp_frame_t *frame, void **args, int nargs) {
    int64_t *p = (int64_t *)UNSAFE_ADDR(args, nargs);
    int64_t expect = (nargs > 3) ? (int64_t)(intptr_t)args[3] : 0, update = (nargs > 4) ? (int64_t)(intptr_t)args[4] : 0;
    frame->result = (void *)(intptr_t)__sync_val_compare_and_swap(p, expect, update); frame->has_result = 1;
}
static void native_unsafe_witnessRef(nabu_interp_frame_t *frame, void **args, int nargs) {
    void **p = (void **)UNSAFE_ADDR(args, nargs);
    void *expect = (nargs > 3) ? args[3] : NULL, *update = (nargs > 4) ? args[4] : NULL;
    frame->result = __sync_val_compare_and_swap(p, expect, update); frame->has_result = 1;
}
static void native_unsafe_getAndAddInt(nabu_interp_frame_t *frame, void **args, int nargs) { NATIVE_RET_INT(frame, __sync_fetch_and_add((int32_t *)UNSAFE_ADDR(args, nargs), (nargs > 3) ? (int32_t)(intptr_t)args[3] : 0)); }
static void native_unsafe_getAndAddLong(nabu_interp_frame_t *frame, void **args, int nargs) { frame->result = (void *)(intptr_t)__sync_fetch_and_add((int64_t *)UNSAFE_ADDR(args, nargs), (nargs > 3) ? (int64_t)(intptr_t)args[3] : 0); frame->has_result = 1; }
static void native_unsafe_getAndSetInt(nabu_interp_frame_t *frame, void **args, int nargs) { NATIVE_RET_INT(frame, __sync_lock_test_and_set((int32_t *)UNSAFE_ADDR(args, nargs), (nargs > 3) ? (int32_t)(intptr_t)args[3] : 0)); }
static void native_unsafe_getAndSetLong(nabu_interp_frame_t *frame, void **args, int nargs) { frame->result = (void *)(intptr_t)__sync_lock_test_and_set((int64_t *)UNSAFE_ADDR(args, nargs), (nargs > 3) ? (int64_t)(intptr_t)args[3] : 0); frame->has_result = 1; }
static void native_unsafe_getAndSetRef(nabu_interp_frame_t *frame, void **args, int nargs) { frame->result = __sync_lock_test_and_set((void **)UNSAFE_ADDR(args, nargs), (nargs > 3) ? args[3] : NULL); frame->has_result = 1; }

static void native_nothing(nabu_interp_frame_t *frame, void **args, int nargs) { (void)args; (void)nargs; frame->has_result = 0; }
static void native_zero(nabu_interp_frame_t *frame, void **args, int nargs) { (void)args; (void)nargs; NATIVE_RET_INT(frame, 0); }
static void native_one(nabu_interp_frame_t *frame, void **args, int nargs) { (void)args; (void)nargs; NATIVE_RET_INT(frame, 1); }
static void native_eight(nabu_interp_frame_t *frame, void **args, int nargs) { (void)args; (void)nargs; NATIVE_RET_INT(frame, 8); }
static void native_pageSize(nabu_interp_frame_t *frame, void **args, int nargs) { (void)args; (void)nargs; NATIVE_RET_INT(frame, 4096); }
static void native_unsafe_allocateInstance(nabu_interp_frame_t *frame, void **args, int nargs) {
    const char *cls = (nargs > 1) ? class_handle_name(args[1]) : NULL;
    frame->result = nabu_interp_new_object(cls); frame->has_result = 1;
}

/* ArraysSupport.hashCode(Object[], from, length, initialValue): 31 * h + hashCode(element) */
static void native_arrayssupport_hashCode(nabu_interp_frame_t *frame, void **args, int nargs) {
    void **a = (nargs > 0 && args[0] != NULL) ? (void **)((char *)args[0] + 8) : NULL;
    int from = nargs > 1 ? (int)(intptr_t)args[1] : 0, len = nargs > 2 ? (int)(intptr_t)args[2] : 0, h = nargs > 3 ? (int)(intptr_t)args[3] : 1;
    for (int i = 0; a != NULL && i < len; i++) h = 31 * h + nabu_interp_hash_code(a[from + i]);
    NATIVE_RET_INT(frame, h);
}

/* SharedSecrets.getJavaLangAccess(): an object whose getEnumConstantsShared(Class) calls values() of the enum. */
static void native_sharedsecrets_jla(nabu_interp_frame_t *frame, void **args, int nargs) {
    (void)args; (void)nargs;
    static void *jla = NULL;
    if (jla == NULL) jla = nabu_interp_new_object("jdk/internal/access/JavaLangAccessImpl");
    frame->result = jla; frame->has_result = 1;
}
static void native_class_getSuperclass(nabu_interp_frame_t *frame, void **args, int nargs) {
    const char *cls = (nargs > 0) ? class_handle_name(args[0]) : NULL;
    const char *sup = NULL;
    if (cls != NULL && cls[0] != '[') { nabu_classfile_load_class(cls); sup = nabu_class_super_name(cls); }
    frame->result = sup != NULL ? make_class_object(sup) : NULL;
    frame->has_result = 1;
}

/* Class.getEnumConstantsShared(): values() of the enum, NULL for a class that is not one. */
static void native_class_getEnumConstantsShared(nabu_interp_frame_t *frame, void **args, int nargs) {
    const char *cls = (nargs > 0) ? class_handle_name(args[0]) : NULL;
    frame->result = NULL; frame->has_result = 1;
    if (cls == NULL) return;
    char desc[300];
    snprintf(desc, sizeof desc, "()[L%s;", cls);
    nabu_classfile_load_class(cls);
    nabu_interp_method_t *t = nabu_interp_find_method(cls, "values", desc);
    if (t != NULL) frame->result = nabu_interp_invoke(t, NULL, 0);
    else if (getenv("NABU_TRACE_EXC")) fprintf(stderr, "no values() for %s\n", cls);
}
/* A field of an interpreted object by name (the value of its slot). */
static void *obj_field(void *obj, const char *cls, const char *name) {
    int fi = nabu_interp_field_index(cls, name);
    return (obj != NULL && fi >= 0) ? ((void **)((char *)obj + sizeof(nabu_object)))[fi] : NULL;
}

/* StringJoiner.toString(): the JDK builds it with StringConcatHelper over byte arrays, which the interpreter does not have. */
static void native_stringjoiner_toString(nabu_interp_frame_t *frame, void **args, int nargs) {
    void *j = nargs > 0 ? args[0] : NULL;
    const char *prefix = (const char *)obj_field(j, "java/util/StringJoiner", "prefix");
    const char *suffix = (const char *)obj_field(j, "java/util/StringJoiner", "suffix");
    const char *delim = (const char *)obj_field(j, "java/util/StringJoiner", "delimiter");
    const char *empty = (const char *)obj_field(j, "java/util/StringJoiner", "emptyValue");
    void *elts = obj_field(j, "java/util/StringJoiner", "elts");
    int size = (int)(intptr_t)obj_field(j, "java/util/StringJoiner", "size");
    if (prefix == NULL) prefix = ""; if (suffix == NULL) suffix = ""; if (delim == NULL) delim = "";
    if (size == 0 && empty != NULL) { frame->result = strdup(empty); frame->has_result = 1; return; }
    void **e = elts != NULL ? (void **)((char *)elts + 8) : NULL;
    size_t cap = strlen(prefix) + strlen(suffix) + 1;
    for (int i = 0; i < size; i++) cap += strlen(e && e[i] ? (const char *)e[i] : "null") + strlen(delim);
    char *r = (char *)malloc(cap);
    strcpy(r, prefix);
    for (int i = 0; i < size; i++) { if (i > 0) strcat(r, delim); strcat(r, e && e[i] ? (const char *)e[i] : "null"); }
    strcat(r, suffix);
    frame->result = r; frame->has_result = 1;
}
static void native_stringjoiner_length(nabu_interp_frame_t *frame, void **args, int nargs) {
    native_stringjoiner_toString(frame, args, nargs);
    NATIVE_RET_INT(frame, (int)strlen((const char *)frame->result));
}

/* CharSequence.toString/length/charAt: a String is a plain char *, anything else is an object that has these methods. */
static int charseq_delegate(nabu_interp_frame_t *frame, void **args, int nargs, const char *name, const char *desc) {
    void *h = nargs > 0 ? args[0] : NULL;
    if (h == NULL || (uintptr_t)h < 0x100000000ull || (uintptr_t)h >= 0x800000000000ull) return 0;
    const char *sbt = nabu_interp_sb_text(h);
    if (sbt != NULL) return 0;   /* a StringBuilder of the natives: its text is its value */
    const char *rt = nabu_compiled_type_name(h);
    nabu_interp_method_t *t = rt != NULL ? nabu_interp_resolve_in_hierarchy(rt, name, desc, 0) : NULL;
    if (t == NULL || (t->code == NULL && !t->is_native) || strcmp(t->class_name, "java/lang/CharSequence") == 0) return 0;
    frame->result = nabu_interp_invoke(t, args, nargs); frame->has_result = 1;
    return 1;
}
static const char *charseq_text(void *h) { const char *t = nabu_interp_sb_text(h); return t != NULL ? t : (h != NULL ? (const char *)h : ""); }
static void native_charseq_toString(nabu_interp_frame_t *frame, void **args, int nargs) {
    if (charseq_delegate(frame, args, nargs, "toString", "()Ljava/lang/String;")) return;
    frame->result = strdup(charseq_text(nargs > 0 ? args[0] : NULL)); frame->has_result = 1;
}
static void native_charseq_length(nabu_interp_frame_t *frame, void **args, int nargs) {
    if (charseq_delegate(frame, args, nargs, "length", "()I")) return;
    NATIVE_RET_INT(frame, (int)strlen(charseq_text(nargs > 0 ? args[0] : NULL)));
}
static void native_charseq_charAt(nabu_interp_frame_t *frame, void **args, int nargs) {
    if (charseq_delegate(frame, args, nargs, "charAt", "(I)C")) return;
    const char *t = charseq_text(nargs > 0 ? args[0] : NULL);
    int i = nargs > 1 ? (int)(intptr_t)args[1] : 0;
    NATIVE_RET_INT(frame, (i >= 0 && i < (int)strlen(t)) ? (unsigned char)t[i] : 0);
}

/* A method of an object of the interpreter, found from its class upwards (iterator(), hasNext(), next() ...). */
static void *call_method_of(void *obj, const char *name, const char *desc) {
    const char *rt = (obj != NULL && (uintptr_t)obj >= 0x100000000ull && (uintptr_t)obj < 0x800000000000ull) ? nabu_compiled_type_name(obj) : NULL;
    nabu_interp_method_t *t = rt != NULL ? nabu_interp_resolve_in_hierarchy(rt, name, desc, 0) : NULL;
    if (t == NULL) return NULL;
    void *a[1] = { obj };
    return nabu_interp_invoke(t, a, 1);
}

/* String.join(CharSequence delimiter, Iterable elements) */
static void native_string_join_iterable(nabu_interp_frame_t *frame, void **args, int nargs) {
    char *delim = nabu_interp_object_to_string(nargs > 0 ? args[0] : NULL);
    void *it = call_method_of(nargs > 1 ? args[1] : NULL, "iterator", "()Ljava/util/Iterator;");
    size_t cap = 64, len = 0;
    char *r = (char *)malloc(cap);
    r[0] = 0;
    int first = 1;
    while (it != NULL && ((intptr_t)call_method_of(it, "hasNext", "()Z") & 1)) {
        char *e = nabu_interp_object_to_string(call_method_of(it, "next", "()Ljava/lang/Object;"));
        size_t need = len + strlen(delim) + strlen(e) + 1;
        if (need > cap) { while (need > cap) cap *= 2; r = (char *)realloc(r, cap); }
        if (!first) { strcpy(r + len, delim); len += strlen(delim); }
        strcpy(r + len, e); len += strlen(e);
        first = 0;
        free(e);
    }
    free(delim);
    frame->result = r; frame->has_result = 1;
}

/* JavaLangAccess.join(prefix, suffix, delimiter, elements, size) for StringJoiner */
static void native_jla_join(nabu_interp_frame_t *frame, void **args, int nargs) {
    const char *prefix = arg_cstr(args, 1, nargs), *suffix = arg_cstr(args, 2, nargs), *delim = arg_cstr(args, 3, nargs);
    void **elems = (nargs > 4 && args[4] != NULL) ? (void **)((char *)args[4] + 8) : NULL;
    int size = nargs > 5 ? (int)(intptr_t)args[5] : 0;
    size_t cap = strlen(prefix) + strlen(suffix) + 1;
    for (int i = 0; i < size; i++) cap += strlen(elems && elems[i] ? (const char *)elems[i] : "null") + strlen(delim);
    char *r = (char *)malloc(cap);
    strcpy(r, prefix);
    for (int i = 0; i < size; i++) { if (i > 0) strcat(r, delim); strcat(r, elems && elems[i] ? (const char *)elems[i] : "null"); }
    strcat(r, suffix);
    frame->result = r; frame->has_result = 1;
}
static void native_jla_getEnumConstantsShared(nabu_interp_frame_t *frame, void **args, int nargs) {
    const char *cls = (nargs > 1) ? class_handle_name(args[1]) : NULL;
    frame->result = NULL; frame->has_result = 1;
    if (cls == NULL) return;
    char desc[300];
    snprintf(desc, sizeof desc, "()[L%s;", cls);
    nabu_classfile_load_class(cls);
    nabu_interp_method_t *t = nabu_interp_find_method(cls, "values", desc);
    if (t != NULL) frame->result = nabu_interp_invoke(t, NULL, 0);
    if (getenv("NABU_TRACE_EXC")) fprintf(stderr, "enum constants of %s: %s\n", cls, frame->result ? "found" : "none");
}

/* Comparable.compareTo(Object) for what the interpreter has no class for: Strings (a char *), boxed numbers (the value itself),
 * tagged Boolean and Character boxes. An object with its own compareTo runs that method. */
static int plain_value(void *v) { return (uintptr_t)v < 0x100000000ull || (uintptr_t)v >= 0x800000000000ull; }

static int compare_numbers(void *a, void *b, int *result);
static void native_comparable_compareTo(nabu_interp_frame_t *frame, void **args, int nargs) {
    void *a = nargs > 0 ? args[0] : NULL, *b = nargs > 1 ? args[1] : NULL;
    { int r; if (a != NULL && compare_numbers(a, b, &r)) { NATIVE_RET_INT(frame, r); return; } }
    if (a != NULL && !plain_value(a) && !nabu_box_kind(a)) {
        const char *rt = nabu_compiled_type_name(a);
        if (rt != NULL) {
            nabu_interp_method_t *t = nabu_interp_resolve_in_hierarchy(rt, "compareTo", "(Ljava/lang/Object;)I", 0);
            if (t == NULL || t->code == NULL) {
                /* javac makes a bridge method for compareTo(T); without it the class has the typed one only */
                char d[300];
                snprintf(d, sizeof d, "(L%s;)I", rt);
                t = nabu_interp_resolve_in_hierarchy(rt, "compareTo", d, 0);
            }
            if (t != NULL && t->code != NULL) { frame->result = nabu_interp_invoke(t, args, nargs); frame->has_result = 1; return; }
        }
        if (!nabu_box_kind(a) && b != NULL && !plain_value(b)) { NATIVE_RET_INT(frame, strcmp((const char *)a, (const char *)b)); return; }   /* Strings */
    }
    char ba[8], bb[8];
    if (nabu_box_kind(a) || nabu_box_kind(b)) { const char *x = nabu_box_kind(a) ? nabu_box_text(a, ba) : "", *y = nabu_box_kind(b) ? nabu_box_text(b, bb) : ""; NATIVE_RET_INT(frame, strcmp(x, y)); return; }
    long x = (long)(intptr_t)nabu_unbox(a), y = (long)(intptr_t)nabu_unbox(b);
    NATIVE_RET_INT(frame, x < y ? -1 : (x > y ? 1 : 0));
}

static void native_integer_compare(nabu_interp_frame_t *frame, void **args, int nargs) {
    int x = nargs > 0 ? (int)(intptr_t)args[0] : 0, y = nargs > 1 ? (int)(intptr_t)args[1] : 0;
    NATIVE_RET_INT(frame, x < y ? -1 : (x > y ? 1 : 0));
}
static void native_long_compare(nabu_interp_frame_t *frame, void **args, int nargs) {
    long x = nargs > 0 ? (long)(intptr_t)args[0] : 0, y = nargs > 1 ? (long)(intptr_t)args[1] : 0;
    NATIVE_RET_INT(frame, x < y ? -1 : (x > y ? 1 : 0));
}

/* Boxing of numbers: the value itself, except 0, which is a tagged box so that it is not the null pointer. */
static void native_box_int(nabu_interp_frame_t *frame, void **args, int nargs) {
    void *v = (nargs > 0) ? args[0] : NULL;
    frame->result = v == NULL ? nabu_box_zero(0) : v; frame->has_result = 1;
}
static void native_box_double(nabu_interp_frame_t *frame, void **args, int nargs) {
    long bits = (nargs > 0) ? (long)(intptr_t)args[0] : 0;
    double d; memcpy(&d, &bits, sizeof d);
    frame->result = nabu_interp_box_double(d); frame->has_result = 1;
}
static void native_box_float(nabu_interp_frame_t *frame, void **args, int nargs) {
    int bits = (nargs > 0) ? (int)(intptr_t)args[0] : 0;
    float f; memcpy(&f, &bits, sizeof f);
    frame->result = nabu_interp_box_float(f); frame->has_result = 1;
}
static void native_box_long(nabu_interp_frame_t *frame, void **args, int nargs) {
    frame->result = nabu_interp_box_long(nargs > 0 ? (int64_t)(intptr_t)args[0] : 0); frame->has_result = 1;
}
/* xxxValue() of a boxed number, converted to the type asked for: the box holds an int (I), long (J), float (F) or double (D). */
static void unbox_convert(nabu_interp_frame_t *frame, void **args, int nargs, char from, char to) {
    void *arg = nargs > 0 ? args[0] : NULL;
    void *raw = nabu_unbox(arg);
    { char k; int64_t bits; if (nabu_interp_heap_box(arg, &k, &bits)) { from = k; raw = (void *)(intptr_t)bits; } }
    double d = 0; long l = 0;
    if (from == 'I') { l = (long)(int)(intptr_t)raw; d = (double)l; }
    else if (from == 'J') { l = (long)(intptr_t)raw; d = (double)l; }
    else if (from == 'F') { int b = (int)(intptr_t)raw; float f; memcpy(&f, &b, sizeof f); d = f; l = (long)f; }
    else { long b = (long)(intptr_t)raw; memcpy(&d, &b, sizeof d); l = (long)d; }
    if (to == 'I') frame->result = (void *)(intptr_t)(int)l;
    else if (to == 'J') frame->result = (void *)(intptr_t)l;
    else if (to == 'F') { float f = (float)d; int b; memcpy(&b, &f, sizeof b); frame->result = (void *)(intptr_t)b; }
    else { long b; memcpy(&b, &d, sizeof b); frame->result = (void *)(intptr_t)b; }
    frame->has_result = 1;
}
#define UNBOX_NATIVE(name, from, to) static void name(nabu_interp_frame_t *frame, void **args, int nargs) { unbox_convert(frame, args, nargs, from, to); }
UNBOX_NATIVE(native_int_to_int, 'I', 'I')
UNBOX_NATIVE(native_int_to_long, 'I', 'J')
UNBOX_NATIVE(native_int_to_float, 'I', 'F')
UNBOX_NATIVE(native_int_to_double, 'I', 'D')
UNBOX_NATIVE(native_long_to_int, 'J', 'I')
UNBOX_NATIVE(native_long_to_long, 'J', 'J')
UNBOX_NATIVE(native_long_to_float, 'J', 'F')
UNBOX_NATIVE(native_long_to_double, 'J', 'D')
UNBOX_NATIVE(native_double_to_int, 'D', 'I')
UNBOX_NATIVE(native_double_to_long, 'D', 'J')
UNBOX_NATIVE(native_double_to_float, 'D', 'F')
UNBOX_NATIVE(native_double_to_double, 'D', 'D')
UNBOX_NATIVE(native_float_to_int, 'F', 'I')
UNBOX_NATIVE(native_float_to_long, 'F', 'J')
UNBOX_NATIVE(native_float_to_float, 'F', 'F')
UNBOX_NATIVE(native_float_to_double, 'F', 'D')

static void native_int_sum(nabu_interp_frame_t *frame, void **args, int nargs) { NATIVE_RET_INT(frame, (int)((nargs > 0 ? (int)(intptr_t)args[0] : 0) + (nargs > 1 ? (int)(intptr_t)args[1] : 0))); }
static void native_int_max(nabu_interp_frame_t *frame, void **args, int nargs) { int a = nargs > 0 ? (int)(intptr_t)args[0] : 0, b = nargs > 1 ? (int)(intptr_t)args[1] : 0; NATIVE_RET_INT(frame, a > b ? a : b); }
static void native_int_min(nabu_interp_frame_t *frame, void **args, int nargs) { int a = nargs > 0 ? (int)(intptr_t)args[0] : 0, b = nargs > 1 ? (int)(intptr_t)args[1] : 0; NATIVE_RET_INT(frame, a < b ? a : b); }
static void native_long_sum(nabu_interp_frame_t *frame, void **args, int nargs) { frame->result = (void *)(intptr_t)((nargs > 0 ? (long)(intptr_t)args[0] : 0) + (nargs > 1 ? (long)(intptr_t)args[1] : 0)); frame->has_result = 1; }
static void native_double_sum(nabu_interp_frame_t *frame, void **args, int nargs) { ret_double(frame, arg_double(args, 0, nargs) + arg_double(args, 2, nargs)); }
/* ---- equals, hashCode, toString and compareTo of boxed values ---- */

/* The number a boxed value stands for: its kind ('I' integral, 'J', 'D', 'F') and bits; 0 when it is none. */
static int number_of(void *v, char *kind, int64_t *bits) {
    if (v == NULL) return 0;
    if (nabu_interp_heap_box(v, kind, bits)) return 1;
    int k = nabu_box_kind(v);
    if (k == 3) { *kind = 'I'; *bits = 0; return 1; }
    if (k) return 0;
    if (plain_value(v)) { *kind = 'I'; *bits = (int64_t)(intptr_t)v; return 1; }
    return 0;
}

static int numbers_equal(void *a, void *b) {
    char ka, kb; int64_t ba, bb;
    if (!number_of(a, &ka, &ba) || !number_of(b, &kb, &bb)) return 0;
    int fa = ka == 'D' || ka == 'F', fb = kb == 'D' || kb == 'F';
    return fa == fb && (!fa || ka == kb) && ba == bb;
}

static void native_number_equals(nabu_interp_frame_t *frame, void **args, int nargs) {
    void *a = nargs > 0 ? args[0] : NULL, *b = nargs > 1 ? args[1] : NULL;
    NATIVE_RET_INT(frame, a == b || numbers_equal(a, b));
}
static void native_number_hashCode(nabu_interp_frame_t *frame, void **args, int nargs) { NATIVE_RET_INT(frame, nabu_interp_hash_code(nargs > 0 ? args[0] : NULL)); }
static void native_number_toString(nabu_interp_frame_t *frame, void **args, int nargs) { frame->result = nabu_interp_object_to_string(nargs > 0 ? args[0] : NULL); frame->has_result = 1; }
static void native_tagged_equals(nabu_interp_frame_t *frame, void **args, int nargs) { NATIVE_RET_INT(frame, nargs > 1 && args[0] == args[1]); }
static void native_tagged_hashCode(nabu_interp_frame_t *frame, void **args, int nargs) { NATIVE_RET_INT(frame, nabu_interp_hash_code(nargs > 0 ? args[0] : NULL)); }

/* Comparison of two boxed numbers (Double.compare semantics for the floating point kinds). */
static int compare_numbers(void *a, void *b, int *result) {
    char ka, kb; int64_t ba, bb;
    if (!number_of(a, &ka, &ba) || !number_of(b, &kb, &bb)) return 0;
    if (ka == 'D' || ka == 'F' || kb == 'D' || kb == 'F') {
        double x, y;
        if (ka == 'D') memcpy(&x, &ba, sizeof x); else if (ka == 'F') { int32_t t = (int32_t)ba; float f; memcpy(&f, &t, sizeof f); x = f; } else x = (double)ba;
        if (kb == 'D') memcpy(&y, &bb, sizeof y); else if (kb == 'F') { int32_t t = (int32_t)bb; float f; memcpy(&f, &t, sizeof f); y = f; } else y = (double)bb;
        *result = x < y ? -1 : (x > y ? 1 : (x == y ? 0 : (x != x ? (y != y ? 0 : 1) : -1)));
    } else *result = ba < bb ? -1 : (ba > bb ? 1 : 0);
    return 1;
}
static void native_double_compare(nabu_interp_frame_t *frame, void **args, int nargs) {
    double x = arg_double(args, 0, nargs), y = arg_double(args, 2, nargs);
    NATIVE_RET_INT(frame, x < y ? -1 : (x > y ? 1 : (x == y ? 0 : (x != x ? (y != y ? 0 : 1) : -1))));
}

static void native_string_lastChars(nabu_interp_frame_t *frame, void **args, int nargs) {   /* isBlank */
    const char *s = arg_cstr(args, 0, nargs);
    int blank = 1;
    for (; *s; s++) if (!isspace((unsigned char)*s)) { blank = 0; break; }
    NATIVE_RET_INT(frame, blank);
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
    register_native("java/lang/Character", "isDigit", "(C)Z", native_char_isDigit);
    register_native("java/lang/Character", "isLetter", "(C)Z", native_char_isLetter);
    register_native("java/lang/Character", "isLetterOrDigit", "(C)Z", native_char_isLetterOrDigit);
    register_native("java/lang/Character", "isUpperCase", "(C)Z", native_char_isUpperCase);
    register_native("java/lang/Character", "isLowerCase", "(C)Z", native_char_isLowerCase);
    register_native("java/lang/Character", "isWhitespace", "(C)Z", native_char_isWhitespace);
    register_native("java/lang/Character", "toUpperCase", "(C)C", native_char_toUpperCase);
    register_native("java/lang/Character", "toLowerCase", "(C)C", native_char_toLowerCase);
    register_native("java/lang/Character", "digit", "(CI)I", native_char_digit);
    register_native("java/lang/Character", "digit", "(II)I", native_char_digit);
    register_native("java/lang/Character", "forDigit", "(II)C", native_char_forDigit);
    register_native("java/lang/Character", "isDigit", "(I)Z", native_char_isDigit);
    register_native("java/lang/Character", "isLetter", "(I)Z", native_char_isLetter);
    register_native("java/lang/Character", "isLetterOrDigit", "(I)Z", native_char_isLetterOrDigit);
    register_native("java/lang/Character", "isWhitespace", "(I)Z", native_char_isWhitespace);
    register_native("java/lang/Character", "isUpperCase", "(I)Z", native_char_isUpperCase);
    register_native("java/lang/Character", "isLowerCase", "(I)Z", native_char_isLowerCase);
    register_native("java/lang/Character", "toUpperCase", "(I)I", native_char_toUpperCase);
    register_native("java/lang/Character", "toLowerCase", "(I)I", native_char_toLowerCase);
    register_native("java/lang/Math", "abs", "(D)D", native_math_abs_d);
    register_native("java/lang/Math", "abs", "(F)F", native_math_abs_f);
    register_native("java/lang/Math", "max", "(DD)D", native_math_max_d);
    register_native("java/lang/Math", "min", "(DD)D", native_math_min_d);
    register_native("java/lang/Math", "max", "(FF)F", native_math_max_f);
    register_native("java/lang/Math", "min", "(FF)F", native_math_min_f);
    register_native("java/lang/Math", "max", "(JJ)J", native_math_max_l);
    register_native("java/lang/Math", "min", "(JJ)J", native_math_min_l);
    register_native("java/lang/Math", "sin", "(D)D", native_math_sin);
    register_native("java/lang/Math", "cos", "(D)D", native_math_cos);
    register_native("java/lang/Math", "tan", "(D)D", native_math_tan);
    register_native("java/lang/Math", "exp", "(D)D", native_math_exp);
    register_native("java/lang/Math", "log", "(D)D", native_math_log);
    register_native("java/lang/Math", "log10", "(D)D", native_math_log10);
    register_native("java/lang/Math", "cbrt", "(D)D", native_math_cbrt);
    register_native("java/lang/Math", "atan2", "(DD)D", native_math_atan2);
    register_native("java/lang/Math", "hypot", "(DD)D", native_math_hypot);
    register_native("java/lang/String", "contains", "(Ljava/lang/CharSequence;)Z", native_string_contains);
    register_native("java/lang/String", "lastIndexOf", "(I)I", native_string_lastIndexOf_char);
    register_native("java/lang/String", "lastIndexOf", "(Ljava/lang/String;)I", native_string_lastIndexOf_str);
    register_native("java/lang/String", "compareTo", "(Ljava/lang/String;)I", native_string_compareTo);
    register_native("java/lang/String", "equalsIgnoreCase", "(Ljava/lang/String;)Z", native_string_equalsIgnoreCase);
    register_native("java/lang/String", "repeat", "(I)Ljava/lang/String;", native_string_repeat);
    register_native("java/lang/String", "substring", "(I)Ljava/lang/String;", native_string_substring_from);
    register_native("java/lang/String", "isBlank", "()Z", native_string_lastChars);
    register_native("jdk/internal/misc/Unsafe", "getUnsafe", "()Ljdk/internal/misc/Unsafe;", native_unsafe_getUnsafe);
    register_native("jdk/internal/misc/Unsafe", "objectFieldOffset", "(Ljava/lang/Class;Ljava/lang/String;)J", native_unsafe_objectFieldOffset);
    register_native("jdk/internal/misc/Unsafe", "objectFieldOffset", "(Ljava/lang/reflect/Field;)J", native_unsafe_objectFieldOffsetField);
    register_native("jdk/internal/misc/Unsafe", "arrayBaseOffset", "(Ljava/lang/Class;)I", native_unsafe_arrayBaseOffset);
    register_native("jdk/internal/misc/Unsafe", "arrayBaseOffset", "(Ljava/lang/Class;)J", native_unsafe_arrayBaseOffsetL);
    register_native("jdk/internal/misc/Unsafe", "arrayIndexScale", "(Ljava/lang/Class;)J", native_unsafe_arrayIndexScaleL);
    register_native("jdk/internal/misc/Unsafe", "arrayIndexScale", "(Ljava/lang/Class;)I", native_unsafe_arrayIndexScale);
    register_native("jdk/internal/misc/Unsafe", "getInt", "(Ljava/lang/Object;J)I", native_unsafe_getInt);
    register_native("jdk/internal/misc/Unsafe", "getLong", "(Ljava/lang/Object;J)J", native_unsafe_getLong);
    register_native("jdk/internal/misc/Unsafe", "getReference", "(Ljava/lang/Object;J)Ljava/lang/Object;", native_unsafe_getRef);
    register_native("jdk/internal/misc/Unsafe", "getByte", "(Ljava/lang/Object;J)B", native_unsafe_getByte);
    register_native("jdk/internal/misc/Unsafe", "getIntVolatile", "(Ljava/lang/Object;J)I", native_unsafe_getInt);
    register_native("jdk/internal/misc/Unsafe", "getLongVolatile", "(Ljava/lang/Object;J)J", native_unsafe_getLong);
    register_native("jdk/internal/misc/Unsafe", "getReferenceVolatile", "(Ljava/lang/Object;J)Ljava/lang/Object;", native_unsafe_getRef);
    register_native("jdk/internal/misc/Unsafe", "getByteVolatile", "(Ljava/lang/Object;J)B", native_unsafe_getByte);
    register_native("jdk/internal/misc/Unsafe", "getIntAcquire", "(Ljava/lang/Object;J)I", native_unsafe_getInt);
    register_native("jdk/internal/misc/Unsafe", "getLongAcquire", "(Ljava/lang/Object;J)J", native_unsafe_getLong);
    register_native("jdk/internal/misc/Unsafe", "getReferenceAcquire", "(Ljava/lang/Object;J)Ljava/lang/Object;", native_unsafe_getRef);
    register_native("jdk/internal/misc/Unsafe", "getByteAcquire", "(Ljava/lang/Object;J)B", native_unsafe_getByte);
    register_native("jdk/internal/misc/Unsafe", "getIntOpaque", "(Ljava/lang/Object;J)I", native_unsafe_getInt);
    register_native("jdk/internal/misc/Unsafe", "getLongOpaque", "(Ljava/lang/Object;J)J", native_unsafe_getLong);
    register_native("jdk/internal/misc/Unsafe", "getReferenceOpaque", "(Ljava/lang/Object;J)Ljava/lang/Object;", native_unsafe_getRef);
    register_native("jdk/internal/misc/Unsafe", "getByteOpaque", "(Ljava/lang/Object;J)B", native_unsafe_getByte);
    register_native("jdk/internal/misc/Unsafe", "putInt", "(Ljava/lang/Object;JI)V", native_unsafe_putInt);
    register_native("jdk/internal/misc/Unsafe", "putLong", "(Ljava/lang/Object;JJ)V", native_unsafe_putLong);
    register_native("jdk/internal/misc/Unsafe", "putReference", "(Ljava/lang/Object;JLjava/lang/Object;)V", native_unsafe_putRef);
    register_native("jdk/internal/misc/Unsafe", "putByte", "(Ljava/lang/Object;JB)V", native_unsafe_putByte);
    register_native("jdk/internal/misc/Unsafe", "putIntVolatile", "(Ljava/lang/Object;JI)V", native_unsafe_putInt);
    register_native("jdk/internal/misc/Unsafe", "putLongVolatile", "(Ljava/lang/Object;JJ)V", native_unsafe_putLong);
    register_native("jdk/internal/misc/Unsafe", "putReferenceVolatile", "(Ljava/lang/Object;JLjava/lang/Object;)V", native_unsafe_putRef);
    register_native("jdk/internal/misc/Unsafe", "putByteVolatile", "(Ljava/lang/Object;JB)V", native_unsafe_putByte);
    register_native("jdk/internal/misc/Unsafe", "putIntRelease", "(Ljava/lang/Object;JI)V", native_unsafe_putInt);
    register_native("jdk/internal/misc/Unsafe", "putLongRelease", "(Ljava/lang/Object;JJ)V", native_unsafe_putLong);
    register_native("jdk/internal/misc/Unsafe", "putReferenceRelease", "(Ljava/lang/Object;JLjava/lang/Object;)V", native_unsafe_putRef);
    register_native("jdk/internal/misc/Unsafe", "putByteRelease", "(Ljava/lang/Object;JB)V", native_unsafe_putByte);
    register_native("jdk/internal/misc/Unsafe", "putIntOpaque", "(Ljava/lang/Object;JI)V", native_unsafe_putInt);
    register_native("jdk/internal/misc/Unsafe", "putLongOpaque", "(Ljava/lang/Object;JJ)V", native_unsafe_putLong);
    register_native("jdk/internal/misc/Unsafe", "putReferenceOpaque", "(Ljava/lang/Object;JLjava/lang/Object;)V", native_unsafe_putRef);
    register_native("jdk/internal/misc/Unsafe", "putByteOpaque", "(Ljava/lang/Object;JB)V", native_unsafe_putByte);
    register_native("jdk/internal/misc/Unsafe", "weakCompareAndSetInt", "(Ljava/lang/Object;JII)Z", native_unsafe_casInt);
    register_native("jdk/internal/misc/Unsafe", "weakCompareAndSetLong", "(Ljava/lang/Object;JJJ)Z", native_unsafe_casLong);
    register_native("jdk/internal/misc/Unsafe", "weakCompareAndSetReference", "(Ljava/lang/Object;JLjava/lang/Object;Ljava/lang/Object;)Z", native_unsafe_casRef);
    register_native("jdk/internal/misc/Unsafe", "weakCompareAndSetIntPlain", "(Ljava/lang/Object;JII)Z", native_unsafe_casInt);
    register_native("jdk/internal/misc/Unsafe", "weakCompareAndSetLongPlain", "(Ljava/lang/Object;JJJ)Z", native_unsafe_casLong);
    register_native("jdk/internal/misc/Unsafe", "weakCompareAndSetReferencePlain", "(Ljava/lang/Object;JLjava/lang/Object;Ljava/lang/Object;)Z", native_unsafe_casRef);
    register_native("jdk/internal/misc/Unsafe", "weakCompareAndSetIntAcquire", "(Ljava/lang/Object;JII)Z", native_unsafe_casInt);
    register_native("jdk/internal/misc/Unsafe", "weakCompareAndSetLongAcquire", "(Ljava/lang/Object;JJJ)Z", native_unsafe_casLong);
    register_native("jdk/internal/misc/Unsafe", "weakCompareAndSetReferenceAcquire", "(Ljava/lang/Object;JLjava/lang/Object;Ljava/lang/Object;)Z", native_unsafe_casRef);
    register_native("jdk/internal/misc/Unsafe", "weakCompareAndSetIntRelease", "(Ljava/lang/Object;JII)Z", native_unsafe_casInt);
    register_native("jdk/internal/misc/Unsafe", "weakCompareAndSetLongRelease", "(Ljava/lang/Object;JJJ)Z", native_unsafe_casLong);
    register_native("jdk/internal/misc/Unsafe", "weakCompareAndSetReferenceRelease", "(Ljava/lang/Object;JLjava/lang/Object;Ljava/lang/Object;)Z", native_unsafe_casRef);
    register_native("jdk/internal/misc/Unsafe", "compareAndSetInt", "(Ljava/lang/Object;JII)Z", native_unsafe_casInt);
    register_native("jdk/internal/misc/Unsafe", "compareAndSetLong", "(Ljava/lang/Object;JJJ)Z", native_unsafe_casLong);
    register_native("jdk/internal/misc/Unsafe", "compareAndSetReference", "(Ljava/lang/Object;JLjava/lang/Object;Ljava/lang/Object;)Z", native_unsafe_casRef);
    register_native("jdk/internal/misc/Unsafe", "compareAndExchangeInt", "(Ljava/lang/Object;JII)I", native_unsafe_witnessInt);
    register_native("jdk/internal/misc/Unsafe", "compareAndExchangeLong", "(Ljava/lang/Object;JJJ)J", native_unsafe_witnessLong);
    register_native("jdk/internal/misc/Unsafe", "compareAndExchangeReference", "(Ljava/lang/Object;JLjava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", native_unsafe_witnessRef);
    register_native("jdk/internal/misc/Unsafe", "getAndAddInt", "(Ljava/lang/Object;JI)I", native_unsafe_getAndAddInt);
    register_native("jdk/internal/misc/Unsafe", "getAndAddLong", "(Ljava/lang/Object;JJ)J", native_unsafe_getAndAddLong);
    register_native("jdk/internal/misc/Unsafe", "getAndSetInt", "(Ljava/lang/Object;JI)I", native_unsafe_getAndSetInt);
    register_native("jdk/internal/misc/Unsafe", "getAndSetLong", "(Ljava/lang/Object;JJ)J", native_unsafe_getAndSetLong);
    register_native("jdk/internal/misc/Unsafe", "getAndSetReference", "(Ljava/lang/Object;JLjava/lang/Object;)Ljava/lang/Object;", native_unsafe_getAndSetRef);
    register_native("jdk/internal/misc/Unsafe", "compareAndExchangeIntAcquire", "(Ljava/lang/Object;JII)I", native_unsafe_witnessInt);
    register_native("jdk/internal/misc/Unsafe", "compareAndExchangeLongAcquire", "(Ljava/lang/Object;JJJ)J", native_unsafe_witnessLong);
    register_native("jdk/internal/misc/Unsafe", "compareAndExchangeReferenceAcquire", "(Ljava/lang/Object;JLjava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", native_unsafe_witnessRef);
    register_native("jdk/internal/misc/Unsafe", "getAndAddIntAcquire", "(Ljava/lang/Object;JI)I", native_unsafe_getAndAddInt);
    register_native("jdk/internal/misc/Unsafe", "getAndAddLongAcquire", "(Ljava/lang/Object;JJ)J", native_unsafe_getAndAddLong);
    register_native("jdk/internal/misc/Unsafe", "getAndSetIntAcquire", "(Ljava/lang/Object;JI)I", native_unsafe_getAndSetInt);
    register_native("jdk/internal/misc/Unsafe", "getAndSetLongAcquire", "(Ljava/lang/Object;JJ)J", native_unsafe_getAndSetLong);
    register_native("jdk/internal/misc/Unsafe", "getAndSetReferenceAcquire", "(Ljava/lang/Object;JLjava/lang/Object;)Ljava/lang/Object;", native_unsafe_getAndSetRef);
    register_native("jdk/internal/misc/Unsafe", "compareAndExchangeIntRelease", "(Ljava/lang/Object;JII)I", native_unsafe_witnessInt);
    register_native("jdk/internal/misc/Unsafe", "compareAndExchangeLongRelease", "(Ljava/lang/Object;JJJ)J", native_unsafe_witnessLong);
    register_native("jdk/internal/misc/Unsafe", "compareAndExchangeReferenceRelease", "(Ljava/lang/Object;JLjava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", native_unsafe_witnessRef);
    register_native("jdk/internal/misc/Unsafe", "getAndAddIntRelease", "(Ljava/lang/Object;JI)I", native_unsafe_getAndAddInt);
    register_native("jdk/internal/misc/Unsafe", "getAndAddLongRelease", "(Ljava/lang/Object;JJ)J", native_unsafe_getAndAddLong);
    register_native("jdk/internal/misc/Unsafe", "getAndSetIntRelease", "(Ljava/lang/Object;JI)I", native_unsafe_getAndSetInt);
    register_native("jdk/internal/misc/Unsafe", "getAndSetLongRelease", "(Ljava/lang/Object;JJ)J", native_unsafe_getAndSetLong);
    register_native("jdk/internal/misc/Unsafe", "getAndSetReferenceRelease", "(Ljava/lang/Object;JLjava/lang/Object;)Ljava/lang/Object;", native_unsafe_getAndSetRef);
    register_native("jdk/internal/misc/Unsafe", "fullFence", "()V", native_nothing);
    register_native("jdk/internal/misc/Unsafe", "loadFence", "()V", native_nothing);
    register_native("jdk/internal/misc/Unsafe", "storeFence", "()V", native_nothing);
    register_native("jdk/internal/misc/Unsafe", "loadLoadFence", "()V", native_nothing);
    register_native("jdk/internal/misc/Unsafe", "storeStoreFence", "()V", native_nothing);
    register_native("jdk/internal/misc/Unsafe", "ensureClassInitialized", "(Ljava/lang/Class;)V", native_nothing);
    register_native("jdk/internal/misc/Unsafe", "shouldBeInitialized", "(Ljava/lang/Class;)Z", native_zero);
    register_native("jdk/internal/misc/Unsafe", "allocateInstance", "(Ljava/lang/Class;)Ljava/lang/Object;", native_unsafe_allocateInstance);
    register_native("jdk/internal/misc/Unsafe", "addressSize", "()I", native_eight);
    register_native("jdk/internal/misc/Unsafe", "pageSize", "()I", native_pageSize);
    register_native("jdk/internal/misc/Unsafe", "isBigEndian", "()Z", native_zero);
    register_native("jdk/internal/misc/Unsafe", "unalignedAccess", "()Z", native_one);
    register_native("jdk/internal/misc/CDS", "getRandomSeedForDumping", "()J", native_zero);
    register_native("jdk/internal/misc/CDS", "isDumpingClassList", "()Z", native_zero);
    register_native("jdk/internal/misc/CDS", "isDumpingArchive", "()Z", native_zero);
    register_native("jdk/internal/misc/CDS", "isSharingEnabled", "()Z", native_zero);
    register_native("jdk/internal/misc/CDS", "initializeFromArchive", "(Ljava/lang/Class;)V", native_nothing);
    register_native("jdk/internal/util/ArraysSupport", "hashCode", "([Ljava/lang/Object;III)I", native_arrayssupport_hashCode);
    register_native("java/lang/Class", "getSuperclass", "()Ljava/lang/Class;", native_class_getSuperclass);
    register_native("java/lang/Class", "getEnumConstantsShared", "()[Ljava/lang/Object;", native_class_getEnumConstantsShared);
    register_native("java/lang/Class", "getEnumConstants", "()[Ljava/lang/Object;", native_class_getEnumConstantsShared);
    register_native("jdk/internal/access/SharedSecrets", "getJavaLangAccess", "()Ljdk/internal/access/JavaLangAccess;", native_sharedsecrets_jla);
    register_native("java/lang/String", "join", "(Ljava/lang/CharSequence;Ljava/lang/Iterable;)Ljava/lang/String;", native_string_join_iterable);
    register_native("java/lang/CharSequence", "toString", "()Ljava/lang/String;", native_charseq_toString);
    register_native("java/lang/CharSequence", "length", "()I", native_charseq_length);
    register_native("java/lang/CharSequence", "charAt", "(I)C", native_charseq_charAt);
    register_native("java/util/StringJoiner", "toString", "()Ljava/lang/String;", native_stringjoiner_toString);
    register_native("java/util/StringJoiner", "length", "()I", native_stringjoiner_length);
    register_native("jdk/internal/access/JavaLangAccess", "join", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;I)Ljava/lang/String;", native_jla_join);
    register_native("jdk/internal/access/JavaLangAccessImpl", "join", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;I)Ljava/lang/String;", native_jla_join);
    register_native("jdk/internal/access/JavaLangAccess", "getEnumConstantsShared", "(Ljava/lang/Class;)[Ljava/lang/Enum;", native_jla_getEnumConstantsShared);
    register_native("jdk/internal/access/JavaLangAccessImpl", "getEnumConstantsShared", "(Ljava/lang/Class;)[Ljava/lang/Enum;", native_jla_getEnumConstantsShared);
    register_native("java/lang/Comparable", "compareTo", "(Ljava/lang/Object;)I", native_comparable_compareTo);
    register_native("java/lang/Integer", "compareTo", "(Ljava/lang/Integer;)I", native_comparable_compareTo);
    register_native("java/lang/Long", "compareTo", "(Ljava/lang/Long;)I", native_comparable_compareTo);
    register_native("java/lang/Integer", "compare", "(II)I", native_integer_compare);
    register_native("java/lang/Integer", "equals", "(Ljava/lang/Object;)Z", native_number_equals);
    register_native("java/lang/Integer", "hashCode", "()I", native_number_hashCode);
    register_native("java/lang/Integer", "toString", "()Ljava/lang/String;", native_number_toString);
    register_native("java/lang/Integer", "compareTo", "(Ljava/lang/Integer;)I", native_comparable_compareTo);
    register_native("java/lang/Long", "equals", "(Ljava/lang/Object;)Z", native_number_equals);
    register_native("java/lang/Long", "hashCode", "()I", native_number_hashCode);
    register_native("java/lang/Long", "toString", "()Ljava/lang/String;", native_number_toString);
    register_native("java/lang/Long", "compareTo", "(Ljava/lang/Long;)I", native_comparable_compareTo);
    register_native("java/lang/Short", "equals", "(Ljava/lang/Object;)Z", native_number_equals);
    register_native("java/lang/Short", "hashCode", "()I", native_number_hashCode);
    register_native("java/lang/Short", "toString", "()Ljava/lang/String;", native_number_toString);
    register_native("java/lang/Short", "compareTo", "(Ljava/lang/Short;)I", native_comparable_compareTo);
    register_native("java/lang/Byte", "equals", "(Ljava/lang/Object;)Z", native_number_equals);
    register_native("java/lang/Byte", "hashCode", "()I", native_number_hashCode);
    register_native("java/lang/Byte", "toString", "()Ljava/lang/String;", native_number_toString);
    register_native("java/lang/Byte", "compareTo", "(Ljava/lang/Byte;)I", native_comparable_compareTo);
    register_native("java/lang/Double", "equals", "(Ljava/lang/Object;)Z", native_number_equals);
    register_native("java/lang/Double", "hashCode", "()I", native_number_hashCode);
    register_native("java/lang/Double", "toString", "()Ljava/lang/String;", native_number_toString);
    register_native("java/lang/Double", "compareTo", "(Ljava/lang/Double;)I", native_comparable_compareTo);
    register_native("java/lang/Float", "equals", "(Ljava/lang/Object;)Z", native_number_equals);
    register_native("java/lang/Float", "hashCode", "()I", native_number_hashCode);
    register_native("java/lang/Float", "toString", "()Ljava/lang/String;", native_number_toString);
    register_native("java/lang/Float", "compareTo", "(Ljava/lang/Float;)I", native_comparable_compareTo);
    register_native("java/lang/Boolean", "equals", "(Ljava/lang/Object;)Z", native_tagged_equals);
    register_native("java/lang/Boolean", "hashCode", "()I", native_tagged_hashCode);
    register_native("java/lang/Boolean", "toString", "()Ljava/lang/String;", native_number_toString);
    register_native("java/lang/Character", "equals", "(Ljava/lang/Object;)Z", native_tagged_equals);
    register_native("java/lang/Character", "hashCode", "()I", native_tagged_hashCode);
    register_native("java/lang/Character", "toString", "()Ljava/lang/String;", native_number_toString);
    register_native("java/lang/Double", "compare", "(DD)I", native_double_compare);
    register_native("java/lang/Integer", "sum", "(II)I", native_int_sum);
    register_native("java/lang/Integer", "max", "(II)I", native_int_max);
    register_native("java/lang/Integer", "min", "(II)I", native_int_min);
    register_native("java/lang/Long", "sum", "(JJ)J", native_long_sum);
    register_native("java/lang/Double", "sum", "(DD)D", native_double_sum);
    register_native("java/lang/Long", "compare", "(JJ)I", native_long_compare);
    register_native("java/lang/StringBuilder", "length", "()I", native_sb_length);
    register_native("java/lang/StringBuilder", "charAt", "(I)C", native_sb_charAt);
    register_native("java/lang/StringBuilder", "setLength", "(I)V", native_sb_setLength);
    register_native("java/lang/StringBuilder", "deleteCharAt", "(I)Ljava/lang/StringBuilder;", native_sb_deleteCharAt);
    register_native("java/lang/String", "split", "(Ljava/lang/String;)[Ljava/lang/String;", native_string_split);
    register_native("java/lang/StringBuilder", "<init>", "()V", native_sb_init);
    register_native("java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", native_sb_init_str);
    register_native("java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", native_sb_append_str);
    register_native("java/lang/StringBuilder", "append", "(I)Ljava/lang/StringBuilder;", native_sb_append_int);
    register_native("java/lang/StringBuilder", "append", "(J)Ljava/lang/StringBuilder;", native_sb_append_long);
    register_native("java/lang/StringBuilder", "append", "(C)Ljava/lang/StringBuilder;", native_sb_append_char);
    register_native("java/lang/StringBuilder", "append", "(Z)Ljava/lang/StringBuilder;", native_sb_append_bool);
    register_native("java/lang/StringBuilder", "append", "(D)Ljava/lang/StringBuilder;", native_sb_append_double);
    register_native("java/lang/StringBuilder", "append", "(F)Ljava/lang/StringBuilder;", native_sb_append_float);
    register_native("java/lang/StringBuilder", "append", "(Ljava/lang/CharSequence;)Ljava/lang/StringBuilder;", native_sb_append_obj);
    register_native("java/lang/StringBuilder", "append", "(Ljava/lang/Object;)Ljava/lang/StringBuilder;", native_sb_append_obj);
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
    register_native("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", native_box_int);
    /* A boxed primitive is its raw value in this interpreter: valueOf and xxxValue are the identity. */
    register_native("java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", native_box_long);
    register_native("java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", native_box_bool);
    register_native("java/lang/Character", "valueOf", "(C)Ljava/lang/Character;", native_box_char);
    register_native("java/lang/Short", "valueOf", "(S)Ljava/lang/Short;", native_box_int);
    register_native("java/lang/Byte", "valueOf", "(B)Ljava/lang/Byte;", native_box_int);
    register_native("java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", native_box_float);
    register_native("java/lang/Integer", "intValue", "()I", native_int_to_int);
    register_native("java/lang/Integer", "longValue", "()J", native_int_to_long);
    register_native("java/lang/Integer", "floatValue", "()F", native_int_to_float);
    register_native("java/lang/Integer", "doubleValue", "()D", native_int_to_double);
    register_native("java/lang/Integer", "shortValue", "()S", native_int_to_int);
    register_native("java/lang/Integer", "byteValue", "()B", native_int_to_int);
    register_native("java/lang/Long", "intValue", "()I", native_long_to_int);
    register_native("java/lang/Long", "floatValue", "()F", native_long_to_float);
    register_native("java/lang/Long", "doubleValue", "()D", native_long_to_double);
    register_native("java/lang/Double", "doubleValue", "()D", native_double_to_double);
    register_native("java/lang/Double", "intValue", "()I", native_double_to_int);
    register_native("java/lang/Double", "longValue", "()J", native_double_to_long);
    register_native("java/lang/Double", "floatValue", "()F", native_double_to_float);
    register_native("java/lang/Float", "floatValue", "()F", native_float_to_float);
    register_native("java/lang/Float", "intValue", "()I", native_float_to_int);
    register_native("java/lang/Float", "longValue", "()J", native_float_to_long);
    register_native("java/lang/Float", "doubleValue", "()D", native_float_to_double);
    register_native("java/lang/Long", "longValue", "()J", native_long_to_long);
    register_native("java/lang/Boolean", "booleanValue", "()Z", native_unbox);
    register_native("java/lang/Character", "charValue", "()C", native_unbox);
    register_native("java/lang/Short", "shortValue", "()S", native_int_to_int);
    register_native("java/lang/Byte", "byteValue", "()B", native_identity_int);
    register_native("java/lang/Float", "floatValue", "()F", native_identity_int);
    register_native("java/lang/Double", "doubleValue", "()D", native_identity_int);
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
    register_native("java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", native_box_double);
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
    register_native("java/lang/Class", "getDeclaredMethod", "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;", native_class_getDeclaredMethod);
    register_native("java/lang/Class", "getMethod", "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;", native_class_getMethod);
    register_native("java/lang/Class", "getDeclaredConstructor", "([Ljava/lang/Class;)Ljava/lang/reflect/Constructor;", native_class_getDeclaredConstructor);
    register_native("java/lang/Class", "getConstructor", "([Ljava/lang/Class;)Ljava/lang/reflect/Constructor;", native_class_getConstructor);
    register_native("java/lang/Class", "getDeclaredField", "(Ljava/lang/String;)Ljava/lang/reflect/Field;", native_class_getDeclaredField);
    register_native("java/lang/Class", "getField", "(Ljava/lang/String;)Ljava/lang/reflect/Field;", native_class_getField);
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
    register_native("java/util/Arrays", "copyOf", "([Ljava/lang/Object;ILjava/lang/Class;)[Ljava/lang/Object;", native_arrays_copyOf_obj);
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
    register_native("java/io/PrintStream", "println", "(Z)V", native_println_boolean);
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
    char dev_base[3][600];
    snprintf(dev_base[0], sizeof(dev_base[0]), "%s/jtest2", nabu_dev_dir());
    snprintf(dev_base[1], sizeof(dev_base[1]), "%s/jtest", nabu_dev_dir());
    snprintf(dev_base[2], sizeof(dev_base[2]), "%s/jdkclasses/classes", nabu_dev_dir());
    const char *bases[] = { dev_base[0], dev_base[1], dev_base[2], NULL };
    /* also NABU_CLASSPATH env (colon-separated) */
    char *env_cp = getenv("NABU_CLASSPATH");
    char env_buf[4096];
    if (env_cp) snprintf(env_buf, sizeof(env_buf), "%s", env_cp);
    else env_buf[0] = 0;

    /* NABU_CLASSPATH first (';' on Windows, ':' elsewhere) */
    if (env_buf[0]) {
#ifdef _WIN32
        const char *sep = ";";
#else
        const char *sep = ":";
#endif
        char *save = NULL;
        char *tok = strtok_r(env_buf, sep, &save);
        while (tok) {
            char buf[1024];
            snprintf(buf, sizeof(buf), "%s/%s.class", tok, tmp);
            FILE *f = fopen(buf, "rb");
            if (f) { fclose(f); return strdup(buf); }
            tok = strtok_r(NULL, sep, &save);
        }
    }
    /* legacy development locations */
    for (int b = 0; bases[b] != NULL; b++) {
        char buf[1024];
        snprintf(buf, sizeof(buf), "%s/%s.class", bases[b], tmp);
        FILE *f = fopen(buf, "rb");
        if (f) { fclose(f); return strdup(buf); }
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

/* The superclass of a loaded class (NULL for Object, interfaces and unknown classes). */
const char *nabu_class_super_name(const char *name) {
    nabu_hier_t *h = name != NULL ? hier_find(name) : NULL;
    return h != NULL ? h->super_name : NULL;
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

static int nabu_classfile_load_bytes(unsigned char *buf, long sz, const char *origin);

/* A class that is an entry of a jar on NABU_CLASSPATH: loaded from the bytes of the entry; -1 when no jar has it. */
static int nabu_classfile_load_from_jar(const char *internal_name) {
    const char *cp = getenv("NABU_CLASSPATH");
    if (cp == NULL || !*cp) return -1;
    char entry[600];
    snprintf(entry, sizeof(entry), "%s.class", internal_name);
    for (char *q = entry; *q; q++) if (*q == '.') *q = '/';
    entry[strlen(entry) - 6] = '.';   /* the dot of ".class" was turned into a slash */
    char *copy = strdup(cp);
    char *save = NULL;
    int rc = -1;
    for (char *dir = strtok_r(copy, NABU_CP_SEP, &save); dir != NULL && rc != 0; dir = strtok_r(NULL, NABU_CP_SEP, &save)) {
        if (!is_jar_path(dir)) continue;
        size_t len = 0;
        unsigned char *bytes = zip_read(dir, entry, &len);
        if (bytes == NULL) continue;
        rc = nabu_classfile_load_bytes(bytes, (long)len, dir);   /* takes the bytes */
    }
    free(copy);
    return rc;
}

int nabu_classfile_load_class(const char *internal_name) {
    /* Skip if already loaded (check if any method registered for this class). */
    if (nabu_interp_find_method(internal_name, NULL, NULL) != NULL) return 0;
    char *path = nabu_find_class_file(internal_name);
    if (path == NULL) return nabu_classfile_load_from_jar(internal_name);
    int rc = nabu_classfile_load(path);
    free(path);
    return rc;
}

int nabu_classfile_load(const char *path) {
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
    return nabu_classfile_load_bytes(buf, sz, path);
}

/* Loads the class file in buf (sz bytes; freed here); origin names where it came from, for messages. */
static int nabu_classfile_load_bytes(unsigned char *buf, long sz, const char *path) {
    register_builtins();
    (void)sz;

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
    /* The fields of the superclass come first in the object. */
    if (super_name != NULL) nabu_classfile_load_class(super_name);
    int base_field_idx = own_field_count(this_class_name);
    if (base_field_idx == 0 && super_name != NULL) base_field_idx = nabu_interp_field_count(super_name);
    for (int i = 0; i < field_count; i++) {
        int faccess = rd_u2(p); p += 2;
        int fname_idx = rd_u2(p); p += 2;
        int fdesc_idx = rd_u2(p); p += 2;
        char *fname = nabu_cp_utf8(cp, fname_idx);
        /* register field */
        nabu_interp_register_field_ex(this_class_name, fname != NULL ? fname : "?", base_field_idx + i,
                                      nabu_cp_utf8(cp, fdesc_idx), faccess);
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
        int maccess = rd_u2(p); p += 2;
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

        m->access = maccess;
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

    hier_register(this_class_name, super_name, iface_names, iface_count);

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

/* ---- Bridge to the compiled runtime (jcl_lite.c): open-world class loading ---- */
extern int (*nabu_interp_load_hook)(const char *internal_name);
extern int (*nabu_interp_call_int_hook)(const char *internal_name, const char *method, int32_t a, int32_t b, int32_t *out);

static void bridge_init_once(void) {
    static int done = 0;
    if (!done) { done = 1; nabu_interp_init(); }
}

static int bridge_load(const char *internal_name) {
    bridge_init_once();
    return nabu_classfile_load_class(internal_name);
}

static int bridge_call_int(const char *internal_name, const char *method, int32_t a, int32_t b, int32_t *out) {
    bridge_init_once();
    if (nabu_interp_find_method(internal_name, method, "(II)I") == NULL) return -1;
    void *args[2];
    args[0] = (void *)(intptr_t)a;
    args[1] = (void *)(intptr_t)b;
    void *r = nabu_interp_invoke_by_name(internal_name, method, "(II)I", args, 2);
    *out = (int32_t)(intptr_t)r;
    return 0;
}

#if defined(__GNUC__) || defined(__clang__)
__attribute__((constructor)) static void nabu_interp_install_hooks(void) {
    nabu_interp_load_hook = bridge_load;
    nabu_interp_call_int_hook = bridge_call_int;
}
#endif

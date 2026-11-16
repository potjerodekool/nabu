#ifndef NABU_INTERPRETER_H
#define NABU_INTERPRETER_H

#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Bootstrap method entry (for invokedynamic). */
typedef struct {
    char *bootstrap_name;   /* e.g. "makeConcatWithConstants" */
    int num_static_args;
    char **static_args;     /* resolved static args (strings) */
} nabu_bootstrap_method_t;

typedef struct {
    int count;
    nabu_bootstrap_method_t *methods;
} nabu_bootstrap_methods_t;
typedef struct {
    unsigned char tag;
    char *utf8;
    int name_index;
    int nat_name, nat_desc;
    int ref_class, ref_nat;
    int int_val;
    long long_val;   /* for CP long (tag 5) and double bits (tag 6) */
    int str_index;
} nabu_cp_entry;

typedef struct {
    nabu_cp_entry *entries;
    int count;
    nabu_bootstrap_methods_t *bootstrap_methods;
} nabu_cp;
typedef struct nabu_interp_method {
    char *class_name;   /* interne naam (pkg/Cls), eigendom */
    char *name;         /* methodenaam, eigendom */
    char *desc;         /* descriptor, eigendom */
    unsigned char *code;
    int code_len;
    int max_locals;
    int max_stack;
    int is_native;
    /* Access flags of the method in the class file (0x0008 = static). */
    int access;
    /* Constant pool of the owning class (for resolving ldc/getstatic at runtime). */
    nabu_cp *const_pool;
    /* Bootstrap methods for invokedynamic (class-level, shared). */
    nabu_bootstrap_methods_t *bootstrap_methods;
    /* Exception table (for try/catch). */
    int exc_count;
    int *exc_start_pc;
    int *exc_end_pc;
    int *exc_handler_pc;
    int *exc_type_idx;   /* CP index of catch type; 0 = catch all */
    /* Name of the owning class (internal). */
    /* (class_name already present) */
    void *native_fn;
    /* JIT-compiled function pointer (Fase 4). NULL if not compiled. */
    void *compiled_fn;
    /* Whether JIT compilation has been attempted. */
    int jit_attempted;
    /* Call counter for hot method detection. */
    int call_count;
    /* Whether the method is deoptimized (compiled_fn should not be used). */
    int deoptimized;
    /* 0 not known yet, 1 a JDK class (null stays lenient), 2 a class of the program (null is a NullPointerException). */
    int null_mode;
    /* Per pc: which of the top stack entries are long or double at pop2/dup2 (found on first use). */
    unsigned char *wide_masks;
    /* Registratie in de globale tabel. */
    int id;
    struct nabu_interp_method *next;
} nabu_interp_method_t;

/* Een frame tijdens uitvoering. */
typedef struct nabu_interp_frame {
    nabu_interp_method_t *method;
    int pc;
    /* Operand stack (void* slots; ints worden als (void*)(intptr_t) opgeslagen). */
    void **stack;
    int stack_top;
    int stack_cap;
    /* Locals. */
    void **locals;
    int locals_cap;
    /* Argumenten (voor de entry-call; bij nested calls worden locals[0..] = args). */
    /* Caller-frame voor return. */
    struct nabu_interp_frame *caller;
    /* Resultaat van de laatste nested call (int of object). */
    void *result;
    int has_result;
} nabu_interp_frame_t;

/* Initialisatie van de interpreter (registries). */
void nabu_interp_init(void);

/* Registreert een methode in de globale tabel; geeft een id terug. */
int nabu_interp_register_method(nabu_interp_method_t *m);

/* Zoekt een methode op id. */
nabu_interp_method_t *nabu_interp_method_by_id(int id);

/* Zoekt een methode op (class, name, desc). Retourneert NULL als niet gevonden. */
nabu_interp_method_t *nabu_interp_find_method(const char *class_name,
                                              const char *name,
                                              const char *desc);

/* Roept een methode aan. Voor int-returning methoden is het resultaat
 * (void*)(intptr_t)intwaarde; voor void is NULL. */
void *nabu_interp_invoke(nabu_interp_method_t *m, void **args, int nargs);

/* Roept een methode aan op basis van (class, name, desc). */
void *nabu_interp_invoke_by_name(const char *class_name, const char *name,
                                 const char *desc, void **args, int nargs);

/* Laadt een .class bestand: registreert alle methoden in de interpreter.
 * Retourneert 0 bij succes. */
int nabu_classfile_load(const char *path);
int nabu_classfile_load_class(const char *internal_name);

/* Interface registry (defined in nabu_classfile.c). */
void nabu_register_interfaces(const char *class_name, char **ifaces, int count);
const char *nabu_class_super_name(const char *class_name);
const char *nabu_interp_uncaught_exception(void);
char *nabu_interp_double_to_string(double v);
char *nabu_interp_float_to_string(float v);
char *nabu_interp_object_to_string(void *obj);
void *nabu_interp_new_object(const char *class_name);
int nabu_interp_hash_code(void *value);
char *nabu_interp_sb_text(void *obj);
nabu_interp_method_t *nabu_interp_resolve_in_hierarchy(const char *cls, const char *name, const char *desc, int depth);
int nabu_get_interface_count(const char *class_name);
char **nabu_get_interfaces(const char *class_name);

/* Itable insert (defined in nabu_interpreter.c). */
void itable_insert(const char *recv_type, const char *iface, const char *mname, const char *mdesc,
                   nabu_interp_method_t *method, void *native_fn, int is_native);

/* Method info registration (defined in nabu_classfile.c). */
void nabu_register_method_info(void *obj, const char *cls, const char *name, const char *desc);

/* Field tracking for interpreted objects. */
int nabu_interp_field_index(const char *class_name, const char *field_name);
int nabu_interp_field_count(const char *class_name);
int nabu_interp_method_count(void);
void nabu_interp_register_field(const char *class_name, const char *field_name, int index);
/* Same, with the field descriptor and access flags (for reflection). */
/* A boxed Integer, Long, Short, Byte, Float or Double is its raw value in the interpreter. A boxed Boolean or Character
 * carries a tag in the upper 16 bits (not a canonical address, so never a pointer), which lets println(Object) and string
 * concatenation print "true" or 'x' instead of 1 or 120. */
#define NABU_BOX_BOOL 0x5A5A000000000000ULL
#define NABU_BOX_CHAR 0x5A5B000000000000ULL
/* A boxed number whose value is 0 (Integer 0, Long 0L, Double 0.0 ...): its raw value would be the null pointer. The low bits
 * say which: 0 integral, 1 double, 2 float. */
#define NABU_BOX_ZERO 0x5A5C000000000000ULL
static inline void *nabu_box_zero(int kind) { return (void *)(uintptr_t)(NABU_BOX_ZERO | (unsigned)kind); }
static inline void *nabu_box_bool(int v) { return (void *)(uintptr_t)(NABU_BOX_BOOL | (v ? 1u : 0u)); }
static inline void *nabu_box_char(int v) { return (void *)(uintptr_t)(NABU_BOX_CHAR | (unsigned)(v & 0xffff)); }
static inline int nabu_box_kind(void *p) {   /* 1 Boolean, 2 Character, 0 not a tagged box */
    uintptr_t t = (uintptr_t)p >> 48;
    return t == 0x5A5A ? 1 : t == 0x5A5B ? 2 : t == 0x5A5C ? 3 : 0;
}
/* The raw value of a possibly boxed primitive. */
static inline void *nabu_unbox(void *p) { int k = nabu_box_kind(p); return k == 3 ? NULL : k ? (void *)(uintptr_t)((uintptr_t)p & 0xffffffffu) : p; }
/* The text of a tagged box ("true", "x"), or NULL when p is not one; buf holds at least 8 bytes. */
static inline const char *nabu_box_text(void *p, char *buf) {
    int k = nabu_box_kind(p);
    if (k == 1) return ((uintptr_t)p & 1) ? "true" : "false";
    if (k == 2) { buf[0] = (char)((uintptr_t)p & 0xff); buf[1] = 0; return buf; }
    if (k == 3) return ((uintptr_t)p & 3) ? "0.0" : "0";
    return NULL;
}

/* The Class object for a class or primitive name (implemented by the class file loader). */
void *nabu_class_object_for_name(const char *name);
/* Throw an exception of the given class (and its superclass) when the current native returns. */
void nabu_interp_throw(const char *class_name, const char *super_class_name);
void nabu_interp_register_field_ex(const char *class_name, const char *field_name, int index, const char *desc, int access);

/* Constant pool helpers (implemented in nabu_classfile.c). */
char *nabu_cp_utf8(nabu_cp *cp, int idx);
char *nabu_cp_class_name(nabu_cp *cp, int idx);

void *nabu_find_native(const char *cls, const char *name, const char *desc);

/* Proxy helpers (defined in nabu_classfile.c). */
int nabu_is_proxy(void *p);
void *nabu_proxy_handler(void *p);

int nabu_interp_methods_for_class(const char *class_name,
                                 nabu_interp_method_t **out, int max);

/* Deoptimize a JIT-compiled method (Fase 4). */
void nabu_interp_deoptimize(nabu_interp_method_t *m);

/* Deoptimize all JIT-compiled methods (Fase 4). */
void nabu_interp_deoptimize_all(void);

/* Try to JIT-compile a method via Cranelift (passes const_pool + method ptr). */
void *nabu_jit_try_compile(nabu_interp_method_t *m);

/* JIT invoke helper: resolve and invoke a method from JIT-compiled code. */
int nabu_jit_invoke_helper(void *method_ptr, int cp_index, int a0, int a1, int a2, int a3, int nargs);

#ifdef __cplusplus
}
#endif

#endif /* NABU_INTERPRETER_H */
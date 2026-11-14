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
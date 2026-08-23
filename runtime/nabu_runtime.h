#ifndef NABU_RUNTIME_H
#define NABU_RUNTIME_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Runtime helpers voor de Nabu LLVM backend.
 *
 * Deze functies worden aangeroepen door LLVM IR dat door de compiler
 * wordt gegenereerd. Ze moeten worden gecompilerd en gelinked met de
 * LLVM output.
 *
 * Compilatie:
 *   gcc -shared -o libnabu_runtime.so nabu_runtime.c -lpthread   (Linux)
 *   gcc -shared -o nabu_runtime.dll  nabu_runtime.c -lpthread   (Windows/MinGW)
 *   clang -shared -o libnabu_runtime.dylib nabu_runtime.c       (macOS)
 */

/**
 * Type descriptor struct voor runtime type info.
 * Elke nabu-klasse krijgt een static instance van dit type.
 */
typedef struct nabu_type_info {
    const char *name;
    size_t      size;
    struct nabu_type_info *super_type;
    int         field_count;
} nabu_type_info;

/**
 * Object header struct — elke heap-object begint hiermee.
 */
typedef struct nabu_object {
    nabu_type_info *type;
    int             ref_count;    /* voor reference counting (niet gebruikt door Boehm) */
    int             monitor_count; /* voor synchronized */
    int             thread_id;    /* welke thread bezit de monitor */
} nabu_object;

/**
 * InstanceOf check.
 * @param obj       Pointer naar het object (mag NULL zijn)
 * @param type_name Interne naam van het type (bv "java/lang/String")
 * @return 1 als het object een instantie is van het type, 0 anders
 */
int nabu_instanceof(void *obj, const char *type_name);

/**
 * Gooi een exception.
 * @param exception Pointer naar het exception-object
 * Wordt nooit teruggekeerd (tenzij geen handler gevonden).
 */
void nabu_throw(void *exception);

/**
 * Vang een exception op — wordt aangeroepen vanuit LLVM landing pads.
 * @param unwind_exc Pointer naar de _Unwind_Exception (van landingpad resultaat)
 * @return Pointer naar het Nabu exception-object
 */
void *nabu_catch(void *unwind_exc);

/**
 * Betreed een monitor (synchronized).
 * @param obj Pointer naar het lock-object
 */
void nabu_monitorenter(void *obj);

/**
 * Verlaat een monitor (synchronized).
 * @param obj Pointer naar het lock-object
 */
void nabu_monitorexit(void *obj);

#ifdef __cplusplus
}
#endif

#endif /* NABU_RUNTIME_H */

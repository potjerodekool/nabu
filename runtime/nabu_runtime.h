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
 * Voeg twee strings samen (concatenatie).
 * @param a Eerste string (mag NULL zijn, wordt als "" behandeld)
 * @param b Tweede string (mag NULL zijn, wordt als "" behandeld)
 * @return Nieuw gealloceerde, nul-afgesloten string a+b
 */
char *nabu_concat(const char *a, const char *b);

/**
 * Gooi een exception.
 * @param exception Pointer naar het exception-object
 * Wordt nooit teruggekeerd (tenzij geen handler gevonden).
 */
void nabu_throw(void *exception);

/**
 * Bepaalt of een gevangen exception-object van het gegeven type is (of een
 * subtype). Wordt in een landing pad gebruikt om een catch-handler al dan
 * niet te selecteren; wanneer dit 0 retourneert moet de handler re-throwen.
 * @param obj        Het gevangen exception-object (nooit NULL)
 * @param type_name  Interne naam van het catch-type (bv "test/Exception")
 * @return 1 als het object een instantie is van het type, 0 anders
 */
int nabu_can_catch(void *obj, const char *type_name);

/**
 * Vang een exception op — wordt aangeroepen vanuit LLVM landing pads.
 * @param unwind_exc Pointer naar de _Unwind_Exception (van landingpad resultaat)
 * @return Pointer naar het Nabu exception-object
 */
void *nabu_catch(void *unwind_exc);

/**
 * Alloceert een object van een onbekende (library)klasse met een geldig
 * nabu_object-header (de type-info wordt per interne naam gecachet).
 * @param type_name Interne naam van de klasse (bv "java/lang/Exception")
 * @param size      Minimale objectgrootte (wordt opgehoogd naar de header)
 * @return Pointer naar het gealloceerde object
 */
void *nabu_new_object(const char *type_name, size_t size);

/**
 * Reflectie-metadata (fase 2): veld- en annotatie-informatie voor klassen
 * die via de native-image-config (reflect-config.json) zijn geregistreerd
 * of runtime-operaties hebben.
 *
 * De backend emitteert per reflectie-geregistreerde klasse een
 * nabu_reflection_info-const-global en roept bij opstart
 * nabu_register_reflection aan.
 */
typedef struct nabu_field_info {
    const char *name;         /* veldnaam */
    const char *type_desc;    /* JVM-descriptor van het veldtype */
    size_t      offset;       /* offset t.o.v. objectbegin */
} nabu_field_info;

typedef struct nabu_annotation_value {
    const char *name;         /* attribuutnaam (mag NULL zijn) */
    const char *text;         /* waarde als string (of ge-renderde array) */
    int         is_string;    /* 1 = stringwaarde, 0 = numeriek/boolean */
    double      num_value;    /* numerieke waarde (of 0/1 voor boolean) */
} nabu_annotation_value;

typedef struct nabu_annotation_entry {
    const char *type_name;    /* interne naam van het annotatietype
                                 (bv "picocli/CommandLine$Option") */
    int         value_count;
    const nabu_annotation_value *values;
} nabu_annotation_entry;

typedef struct nabu_reflection_info {
    const char *type_name;    /* interne naam van de eigen klasse */
    int         field_count;
    const nabu_field_info *fields;
    int         annotation_count;
    const nabu_annotation_entry *annotations;
} nabu_reflection_info;

/**
 * Registreert ('open declareert') een reflectie-metadata-tabel.
 * Wordt door de backend per geregistreerde klasse aangeroepen (bij opstart
 * vanuit de <clinit>-volgorde van de module).
 * @param info De metadata-const (blijft geldig voor de levensduur van het proces)
 * @return 1 bij succes
 */
int nabu_register_reflection(const nabu_reflection_info *info);

/**
 * Opzoeken van reflectie-metadata per interne naam; NULL als onbekend.
 */
const nabu_reflection_info *nabu_lookup_reflection(const char *internal_name);

/**
 * Offset van een veld bij naam; -1 wanneer onbekend.
 */
long nabu_reflect_field_offset(const char *type_name, const char *field_name);

/**
 * Annotatie-entry opzoeken op klasse (of NULL).
 */
const nabu_annotation_entry *nabu_get_annotation(const char *type_name,
                                                 const char *annotation_type_name);

/**
 * Constructor van java.lang.Exception (runtime-definitie; vult geen velden).
 */
void java_lang_Exception_init(void *self);

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

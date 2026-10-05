#ifndef NABU_RUNTIME_H
#define NABU_RUNTIME_H

#include <stddef.h>
#include <stdint.h>

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
 *
 * De veldvolgorde MOET overeenkomen met ClassLayouts.objectHeaderType() in de
 * native-backend (`{ ptr type, ptr vtable, ptr itable, i32 ref_count, i32
 * monitor, i32 thread }`): de backend schrijft type/vtable/itable zelf in de
 * header, en de runtime leest/schrijft ref_count/monitor/thread_id. Op een
 * 64-bittig target geeft dat offsets 0/8/16/24/28/32 en grootte 40; de
 * C-struct hieronder heeft precies dezelfde layout omdat alle leden
 * standaard-uitlijning hebben.
 *
 * Let op: de `monitor_*`-leden worden door nabu_monitorenter/exit gebruikt en
 * stonden vóór dit schema op offset 8 — dus over de vtable heen.
 */
typedef struct nabu_object {
    void  *type;          /* offset 0  — nabu_type_info* */
    void  *vtable;        /* offset 8  — door de backend gevuld */
    void  *itable;        /* offset 16 — door de backend gevuld */
    int32_t ref_count;    /* offset 24 — voor reference counting */
    int32_t monitor_count;/* offset 28 — voor synchronized */
    int32_t thread_id;    /* offset 32 — welke thread bezit de monitor */
} nabu_object;

/** Minimale objectgrootte: één header zonder instantievelden. */
#define NABU_HEADER_SIZE ((size_t) sizeof(nabu_object))

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
 * Zet een geheel getal om naar zijn decimale stringrepresentatie, zoals Java's
 * {@code String.valueOf(int)} / {@code "" + int}. Nodig voor string-concat met
 * een primitieve operand (bv. {@code arity.min + "..*"}), die de JVM met
 * StringBuilder doet maar die een native backend zelf moet vormen.
 * @param value Het getal
 * @return Nieuw gealloceerde, nul-afgesloten string (nooit NULL)
 */
char *nabu_int_to_string(int32_t value);

/** Als {@link nabu_int_to_string}, maar voor 64-bits gehele getallen. */
char *nabu_long_to_string(int64_t value);

/** Converteert een double naar een Java-achtige stringrepresentatie. */
char *nabu_double_to_string(double value);

/** Converteert een float naar een Java-achtige stringrepresentatie. */
char *nabu_float_to_string(float value);

/** "true" of "false", zoals Java's Boolean.toString. */
char *nabu_bool_to_string(int32_t value);

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
 * Her-gooi de exception die naar het landingspad van deze frame leidde.
 *
 * Wordt gebruikt in het rethrow-pad van een type-check: wanneer geen enkele
 * catch-clause van deze frame matcht, moet de unwind doorgaan naar de
 * omringende handlers — dat is een {@code _Unwind_Resume} op dezelfde
 * {@code _Unwind_Exception}, niet een nieuw {@code nabu_throw}. Een nieuwe
 * throw zou een verse exception starten op de rethrow-locatie, buiten het
 * beschermde bereik, en daarmee de omringende handlers in thezelfde frame
 * overslaan.
 *
 * @param unwind_exc De rauwe {@code _Unwind_Exception}-pointer die het
 *                   landingspad ontving (dus nog vóór {@code nabu_catch}).
 * Wordt nooit teruggekeerd (tenzij er nergens een handler is).
 */
void nabu_resume(void *unwind_exc);

/**
 * Initialiseert de object-header van een object van een onbekende (library)-
 * klasse, zodat nabu_instanceof/nabu_can_catch erop werken (de type-info wordt
 * per interne naam gecachet).
 *
 * De *allocatie* gebeurt bewust door de backend, niet hier: alleen die weet
 * welke GC-strategie geldt. BOEHM moet via GC_malloc, REFCOUNT via malloc en
 * NONE via de stack. Als de runtime zelf mallocte, zouden zulke objecten onder
 * BOEHM onzichtbaar zijn voor de collector en dus permanent lekken.
 *
 * @param obj       Het gealloceerde geheugen, minstens {@code sizeof(nabu_object)} bytes
 * @param type_name Interne naam van de klasse (bv "java/lang/Exception")
 * @return Dezelfde pointer als `obj`
 */
void *nabu_init_object(void *obj, const char *type_name);

/* -------------------------------------------------------
 * Reference counting (GcStrategy.REFCOUNT)
 * -------------------------------------------------------
 *
 * De LLVM-backend gebruikt een sterke-eigenaar-semantiek: een heap-allocatie
 * krijgt één retain, en die eigenaar geeft hem weer af op de plek waar de
 * toewijzing 'dood' gaat — hetzij bij overschrijving van een referentie-slot,
 * hetzij in de exit-pad van de frame die 'm alloceerde. Een allocatie die de
 * frame ontsnapt (returnwaarde, object-veld, call-argument, throw) krijgt
 * bewust géén retain en wordt ook niet vrijgegeven: de ontvanger is eigenaar
 * en de backend weet niet hoe die 'm beheert. Dat is de belangrijkste
 * bekende beperking van REFCOUNT.
 *
 * Beide functies zijn NULL-safe: nabu_retain(NULL) en nabu_release(NULL) zijn
 * no-ops. De backend hoeft daardoor geen null-tests te emitteren; de
 * identiteits-guard (is dit werkelijk een van onze eigen objecten?) gebeurt in
 * het gegenereerde IR, niet hier.
 *
 * nabu_release geeft het geheugen vrij zodra de teller op 0 komt. Een release
 * op een teller die al 0 (of negatief) is, doet niets: dat pad hoort niet voor
 * te komen en zou anders een dubbele free zijn. Er is bewust GEEN
 * finalizer/destructor-traversaal: een object dat door een ander object
 * bereikbaar is gemaakt (een object-graph, en dus ook een cyclus) wordt niet
 * meegezwopt. Dat is de gedocumenteerde beperking van deze strategie; wie die
 * nodig heeft kiest BOEHM.
 */

/** Verhoogt de referentieteller van obj met 1. */
void nabu_retain(void *obj);

/** Verlaagt de referentieteller; vrijgeeft zodra deze 0 wordt. NULL-safe. */
void nabu_release(void *obj);

/**
 * Huidige referentieteller van obj, of -1 voor NULL. Diagnostiek: het
 * nadeel van het meelezen van een teller is dat het NA een release een
 * use-after-free is; lees hem dus alleen voor de release.
 */
int nabu_ref_count(void *obj);

/**
 * Aantal objecten dat sinds processtart in beheer is genomen: één per
 * nabu_retain-call met een niet-NULL argument. NULL-calls tellen niet mee,
 * want die veranderen geen eigendom.
 */
long nabu_rc_retain_count(void);

/** idem voor nabu_release; zie nabu_rc_retain_count. */
long nabu_rc_release_count(void);

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
    const char *owner;        /* veldnaam bij veld-annotaties, NULL bij klasse-annotaties */
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
 * Retourneert intptr_t (64-bit op alle 64-bit targets) zodat de IR
 * (i64) de waarde rechtstreeks overneemt — C `long` is op Windows
 * x64 slechts 32-bit (LLP64) en zou -1 als 0x00000000FFFFFFFF lezen.
 */
intptr_t nabu_reflect_field_offset(const char *type_name, const char *field_name);

/**
 * Annotatie-entry opzoeken op klasse (klasse-annotaties; owner == NULL) of NULL.
 */
const nabu_annotation_entry *nabu_get_annotation(const char *type_name,
                                                 const char *annotation_type_name);

/**
 * Annotatie-entry opzoeken op veld (velden die van dit type gemerkt zijn) of NULL.
 */
const nabu_annotation_entry *nabu_get_field_annotation(const char *type_name,
                                                       const char *field_name,
                                                       const char *annotation_type_name);

/**
 * Getypte reflectieve veldtoegang (onderlegger voor een lite
 * java.lang.reflect.Field). De offset komt uit de geregistreerde
 * reflectie-metadata.
 *
 * Bij een onbekend type, veld of NULL-object retourneren de getters 0 / NULL.
 */
int   nabu_reflect_get_i32(void *obj, const char *type_name, const char *field_name);
void  nabu_reflect_set_i32(void *obj, const char *type_name, const char *field_name, int value);
int64_t nabu_reflect_get_i64(void *obj, const char *type_name, const char *field_name);
void  nabu_reflect_set_i64(void *obj, const char *type_name, const char *field_name, int64_t value);
float nabu_reflect_get_f32(void *obj, const char *type_name, const char *field_name);
void  nabu_reflect_set_f32(void *obj, const char *type_name, const char *field_name, float value);
double nabu_reflect_get_f64(void *obj, const char *type_name, const char *field_name);
void  nabu_reflect_set_f64(void *obj, const char *type_name, const char *field_name, double value);
void  *nabu_reflect_get_ref(void *obj, const char *type_name, const char *field_name);
void  nabu_reflect_set_ref(void *obj, const char *type_name, const char *field_name, void *value);

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

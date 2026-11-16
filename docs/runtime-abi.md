# Nabu Runtime ABI

Dit document bevriest het runtime ABI van Nabu zodat de AOT-backend
(`native-backend`) en de bytecode-interpreter (GraalVM Next-achtige runtime)
hetzelfde objectmodel delen. Elke wijziging aan deze layout vereist een
update van zowel de backend (`ClassLayouts.java`) als dit document en een
migratie van de interpreter.

Bron: `runtime/nabu_runtime.h`. Backend-referentie: `native-backend/.../ClassLayouts.java`.

## 1. Object-header (`nabu_object`)

Elk heap-object begint met een header van **16 bytes** op een 64-bit target:

| Offset | Veld | Type | Beschrijving |
|---|---|---|---|
| 0 | `type` | `nabu_type_info *` | Type-descriptor van de klasse |
| 8 | `monitor_count` | `int32_t` | Synchronized monitor count |
| 12 | `thread_id` | `int32_t` | Thread die de monitor bezit |

Direct na de header volgen de instantievelden (super-klasse eerst, dan eigen),
in dezelfde volgorde als in de class-file. De backend berekent de offsets via
`ClassLayouts` (veldindex → struct-index).

Regels:
- Een `NULL`-pointer is geen object (geen header).
- De header wordt door de backend gealloceerd (GC-strategie bepaalt malloc
  vs. stack); de runtime initialiseert alleen de `type`-pointer en nult de
  rest (`nabu_init_object` / `nabu_init_object_ex`).
- Voor library-klassen (jcl_lite) wordt één extra pointerwoord gereserveerd
  na de header (`JCL_STATE_SLOT`) voor de runtime-struct.

## 2. Type-descriptor (`nabu_type_info`)

| Veld | Type | Beschrijving |
|---|---|---|
| `name` | `const char *` | Interne naam (`pkg/Cls`, met `/`) |
| `size` | `size_t` | sizeof van het object (header + velden) |
| `super_type` | `nabu_type_info *` | Super-klasse, of NULL (voor Object) |
| `field_count` | `int` | Aantal velden (incl. geërfde, voor compat) |
| `interfaces` | `const char *const *` | Geïmplementeerde interfaces (incl. super) |
| `interface_count` | `int` | Aantal interfaces |
| `instance_fields` | `const nabu_type_field *` | Eigen instantievelden (naam + offset) |
| `instance_field_count` | `int` | Aantal eigen instantievelden |
| `vtable` | `void **` | Vtable-array van functiepointers |
| `itable` | `void **` | Itable-array van functiepointers |

Regels:
- `name` is een stabiele, nul-afgesloten string (eigendom van de caller of
  gekopieerd door `nabu_register_interpreted_type`).
- `super_type` vormt een keten tot Object (of NULL).
- `instanceof` loopt deze keten af en controleert ook `interfaces`.
- vtable/itable mogen NULL zijn; de runtime valt terug op Object's tabellen
  voor library-typen.

## 3. Vtable / itable

- Vtable: array van `void *` (functiepointers), één slot per virtuele methode.
  Slots worden gedeeld tussen super- en subklasse (override vervangt op dezelfde
  positie). Sortering: op methodenaam (full internal name).
- Itable: array van `void *`, één slot per interface-methode.
- Lege vtable/itable wordt als `LLVMConstNull` / NULL geregistreerd.

De backend bouwt deze bij compile-time (`ClassLayouts.buildVtable` /
`buildItable`). De interpreter bouwt ze bij class-load en registreert ze via
`nabu_register_interpreted_type`.

## 4. Registratie van types

### Gecompileerde types
`nabu_register_type_infos(table, count)` registreert een array van
`nabu_type_info *` (één per gecompileerde klasse). Lookup via
`nabu_compiled_type_for(name)` (hash-index, O(1)).

### Interpreted types
`nabu_register_interpreted_type(name, super_name, size, vtable, itable)`
registreert een runtime-aangemaakt type. Lookup via
`nabu_interpreted_type_for(name)` (lineair) of via de fallback in
`nabu_compiled_type_for` (zodat cross-boundary code één lookup-functie kan
gebruiken).

Idempotent: een tweede registratie met dezelfde naam retourneert het bestaande
type en werkt vtable/itable/super bij.

### Library types (jcl_lite)
`nabu_type_info_for(name)` maakt een cache-entry in `nabu_type_cache`
(1024 slots). Deze worden gebruikt voor objecten beheerd door de C-runtime
(Class, ClassLoader, ...). Super-keten voor kern-exception-klassen wordt via
`nabu_builtin_super_of` ingevuld.

## 5. Object-herkenning

`nabu_jcl_is_object(p)` retourneert 1 als `p`'s eerste woord (type-ptr) wijst
naar een entry in `nabu_type_cache` of de interpreted-registry. Wordt gebruikt
in `nabu_instanceof` om C-strings/literals van echte objecten te onderscheiden.

`nabu_compiled_type_name(p)` retourneert de interne naam als `p`'s type-ptr
in het executable-image ligt of in de interpreted-registry. Wordt gebruikt voor
caching van instanceof-resultaten.

## 6. GC-contract

- Alle heap-allocation gaat via `nabu_gc_alloc` / `nabu_gc_alloc_atomic` /
  `nabu_gc_alloc_pinned`.
- De runtime gebruikt `nabu_gc_alloc_pinned` voor process-lifetime data
  (type-tabellen, vtables).
- Interpreted types worden met `calloc`/`malloc` gemaakt en leven voor de
  duur van het proces (geen free). Boehm scant conservatief, dus geen lek
  zolang er een root naar bestaat; malloc'd memory is niet Boehm-owned maar
  wordt nooit gecollecteerd (acceptabel voor process-lifetime).
- Object-allocatie door de interpreter moet dezelfde GC-strategie gebruiken als
  de backend (Boehm default).

## 7. Threading

- `nabu_rt_lock` / `nabu_rt_unlock` beschermen de type-caches.
- Type-registratie gebeurt typisch single-threaded (class-loading), maar de
  lock maakt het veilig.

## 8. Compatibiliteit met de backend

De volgende invarianten moeten tussen backend en interpreter gehandhaafd
blijven:

1. Header-layout (offset 0/8/12, grootte 16).
2. `nabu_type_info`-veldvolgorde en -grootte.
3. Vtable-slot-volgorde (per klasse, gesorteerd op methodenaam, overrides op
   dezelfde positie als super).
4. Interne naamconventie (`pkg/Cls` met `/`).
5. Super-keten (super eerst in de struct-layout).
6. `instanceof`-semantiek (super + interfaces).

Bij elke backend-wijziging aan `ClassLayouts` of `nabu_runtime.h` moet dit
document en de interpreter bijgewerkt worden.

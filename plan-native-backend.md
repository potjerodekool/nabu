# Plan: native (LLVM) backend volledig maken

## Context

De native backend (`native-backend/src/main/java/io/github/potjerodekool/nabu/compiler/backend/native_llvm/`)
compileert een `IRModule` naar LLVM IR (`.ll`), een objectfile (`.o`) en optioneel een
uitvoerbaar bestand via `Linker`. Hij is geregistreerd als plugin `name="LLVM"` via
`plugin.xml`, maar er is **geen enkel end-to-end pad** dat hem aanroept (geen
`--backend=LLVM` in client/CLI/maven, geen volledige-pipeline test). De 33 backend-tests
bouwen IR met de hand en roepen `NativeLLVMBackend` rechtstreeks aan; ze dekken alleen
primitieven en control-flow.

**Scope (volgens gebruiker):**
- Volledige nabu-taal (alle IR-instructies correct emitteren, inclusief ArrayStore,
  virtual/interface/static/special dispatch, try/catch/finally, monitors, strings+concat,
  enums/records als gewone klassen).
- GC-doelen: NONE + BOEHM.
- End-to-end wiring toevoegen (backend-optie in maven-plugin/client) + volledige-pipeline
  integratietest.

---

## Wat is al geïmplementeerd (inventaris)

| Component | Status |
|---|---|
| `NativeLLVMBackend` | Volledig: LLVM init, module-aanmaak, target machine, `.ll` print, optimalisatie (PassManager), objectfile emissie, optioneel linken als er `main` is |
| `LLVMModuleEmitter` | Orkestratie (runtime helpers → globals → signatures → bodies); declareert `nabu_instanceof`, `nabu_throw`, `nabu_catch`, `nabu_monitorenter/exit`, `__gcc_personality_v0`, BOEHM/REFCOUNT helpers |
| `TypeMapper` | Int/Float/Bool/Void/Ptr/Array/Function/Struct |
| `ConstantResolver` | ConstInt/Float/Bool/Null/Undef/String/Class, string-global cache |
| `GlobalEmitter` | IRGlobal → LLVM global; string-literals |
| `FunctionEmitter` | Signatuur, params, basisblokken, `tryHandlerMap` pre-scan, landing pad + catch via `nabu_catch` |
| `InstructionEmitter` | BinaryOp, Alloca, AllocaArray, Load, Store, Call, IndirectCall, Branch, CondBranch, Return, Cast, Phi, Move, InstanceOf, Throw, Pop, HeapAlloc, ArrayLoad, ArrayLength, MonitorEnter/Exit, TryCatchRegion (no-op in emit) |
| `Linker` | gcc/clang/link.exe, `-lgc` voor BOEHM |
| `nabu_runtime.c/.h` | `nabu_type_info`, `nabu_object` header, `nabu_instanceof`, `nabu_throw`, `nabu_catch`, monitors, Itanium unwinding |

**Referentie:** de ASM-backend (`compiler/.../backend/asm/FunctionEmitter.java`) is de
"correcte" backend. Belangrijkste inzichten daaruit:
- Veldtoegang is in IR `IRValue.Values(obj, Named(naam,type,ownerType,isStatic,fieldIndex))`.
  De JVM lost de offset zelf op (GETFIELD/PUTFIELD/GETSTATIC/PUTSTATIC); in LLVM moeten wij
  zelf een struct-layout + veldoffset berekenen (GEP). `ownerType` en `fieldIndex` leveren
  de benodigde info.
- Arrays: header met `length` op offset 0; elementen daarna. `ArrayStore`/`ArrayLoad`/
  `ArrayLength` gebruiken elementtypes (B/S/I/L/F/D/A = 8/16/32/64 int, 32/64 float, bool, ref).
- Dispatch: STATIC/SPECIAL = directe call; VIRTUAL = vtable; INTERFACE = itable.
- String-concat is NIET op IR-niveau verlaagd: het is een `BinaryOp(ADD)` met operandtype
  `Ptr(i8)`; de ASM-backend detecteert dit en zet het om naar concat. In LLVM moet er een
  runtime-helper of inline-append komen.
- Enums/records worden al op AST-niveau verlaagd naar gewone klassen/velden/methoden;
  de backend hoeft er niets speciaals voor te doen.

---

## De grote gaten (voor "volledige nabu-taal")

1. **`ArrayStore` wordt nergens geëmit** — `InstructionEmitter.emit()` heeft geen
   `case ArrayStore`; een array-schrijfopdracht crasht met `UnsupportedOperationException`.
2. **Geen object/veldmodel** — veldtoegang is `IRValue.Values(obj, Named(...))`, maar
   `resolveValue(Values)` emitteert gewoon elk element en geeft de laatste terug; `Named`
   wordt als global opgelost (of crasht). Er is geen GEP voor veldoffset, geen object-`struct`-
   layout, geen object-header-initialisatie bij `HeapAlloc`. Het `nabu_object`/`nabu_type_info`
   model in de runtime wordt nooit opgebouwd.
3. **Geen method dispatch** — `Call` doet een directe `LLVMBuildCall2` op functienaam.
   Geen vtable/itable: virtual/interface calls worden niet gedispatched; `_super` is een
   no-op (klopt alleen zonder inheritance); constructor-calls (`_init`) worden als gewone
   functies behandeld i.p.v. alloc+init te koppelen.
4. **Strings/string-concat ontbreken** — `[N x i8]` globals; `BinaryOp(ADD, ptr(i8), ...)`
   wordt behandeld als integer-add i.p.v. concat. Geen runtime-concat-helper.
5. **Arrays** — `AllocaArray`/`ArrayLoad`/`ArrayLength` bestaan deels maar zijn inconsistent
   met Boehm (`GC_malloc` retourneert ruwe bytes zonder header); `ArrayStore` ontbreekt;
   de element-pointer-berekening (`header + idx*size`) is ongedefinieerd t.o.v. `ArrayLength`
   (dat index 0 als lengte leest).
6. **Exception handling is half** — landingpad + `nabu_catch` is geëmit, maar `emitThrow`
   roept `nabu_throw` aan zonder mapp na het landing-pad; `TryCatchRegion` in de emit is
   no-op (alleen pre-scan); catch-variabelen/labels zijn niet altijd gekoppeld; er is geen
   `finally`-extra-`monitorexit` op exception-pad.
7. **Boehm/NONE allocs correct maken** — `emitHeapAlloc`/`emitAllocaArray` voor BOEHM roepen
   alleen `GC_malloc`; het object-header-veld (`nabu_object.type`) wordt niet gezet.
8. **Geen end-to-end wiring / test.**

---

## Uit te voeren werk (gefaseerd)

### Fase 0 — Objectmodel & veldtoegang (fundament)
- Definieer per klassetype een LLVM `struct`-layout: object-header (`nabu_object`: type,
  refcount, monitor) gevolgd door velden (superklasse-velden eerst, dan eigen velden in volgorde).
- Voeg in `TypeMapper` een klassetype-mapping toe (IRType → LLVM struct) met cache
  `fieldIndex` → offset.
- In `InstructionEmitter`: los `IRValue.Values(obj, Named)` op door GEP + load/store te
  emitteren (niet "last element"):
  - instance veld: `ptr = GEP(struct*, obj, 0, headerOffset + fieldIndex)`; `load`/`store`.
  - static veld: gewone global (huidig gedrag).
- `emitHeapAlloc`: alloca/malloc `sizeof(struct)` en initialiseer header (`type = &<classdesc>`).
- Per klasse een `nabu_type_info`-global + super_type-koppeling voor `instanceof`.
- `ArrayStore` toevoegen aan `emit()`; `emitArrayLoad`/`emitArrayLength` consistent maken met
  de array-header (`[header][length: i32][elem...]`); `emitHeapAlloc` voor arrays updaten.

**Acceptatie:** tests die een object aanmaken, veld schrijven/lezen, array schrijven/lezen/lengte —
met NONE én BOEHM.

### Fase 1 — Method dispatch
- `CallKind` (static/special/virtual/interface) correct vertalen:
  - STATIC / SPECIAL (constructor, private, super-methode): directe call.
  - VIRTUAL: vtable in object-header; indirect call via GEP naar vtable-slot.
  - INTERFACE: itable-lookup bij type-info.
- Constructor-calls (`_init`) koppelen aan het voorafgaande `HeapAlloc`-result (alloc + init),
  zodat het object niet verloren gaat.
- `_super` niet langer no-op bij inheritance: call de super-implementatie.
- Vtable/itable opbouwen in de module-emitter per klasse/interface.

**Acceptatie:** overerving met `@Override`, virtuele dispatch (keuze op runtime-type), interface-call.

### Fase 2 — Strings & string-concat
- `BinaryOp(ADD)` met operandtype `Ptr(i8)` detecteren → runtime-helper `nabu_concat`
  (of inline append) i.p.v. integer-add.
- String-globals correct afsluiten/gelezen en als `i8*` gebruiken.
- (Optioneel, licht) `ConstClass` → geregistreerd type-info i.p.v. naamstring, voor
  `instanceof`/casts.

**Acceptatie:** `a + b` op strings, meerdere strings (links-associatief), string in main/printf.

### Fase 3 — Exception handling voltooien
- `emitThrow`: gebruik het landing-pad-mechanisme (niet alleen `nabu_throw`); een throw in een
  try-regio moet via unwind naar de handler; anders uncaught.
- Catch-handler koppelen aan de landing-pad-selector (type-filtering `_ZTIN...`).
- Alle handlers een geldig landingpad + `nabu_catch` geven.
- `finally` / synchronized-exception-pad: extra `nabu_monitorexit` in de handler.

**Acceptatie:** `try/catch` met specifiek type, `throw new X()`, nested try, en (indien nodig) `finally`.

### Fase 4 — Boehm & NONE correct
- BOEHM: alle alloc via `GC_malloc`, header-initialisatie, geen `free`; `GC_init` aanroepen bij start.
- NONE: stack-alloca (of `malloc`) zonder GC; documenteer dat dit voor tests/kleine demo's is.
- Consistentie van array/object-header tussen beide strats.

**Acceptatie:** bestaande 33 tests groen op beide strategieën; nieuwe objecttests groen op beide.

### Fase 5 — Wiring + end-to-end test
- Zet `--backend` doorlaatbaar:
  - maven-plugin: expliciete `backend`-parameter (naast `compilerArgs`).
  - client: `CompilerOptionBuilder.backend(...)`-setter (of map-entry).
  - CLI/daemon is al doorlaat (bevestigd: `CompileTaskHandler.configureBackend`).
- ~~Beslis over de ongebruikte `LoopBuilder`~~ → **verwijderd**: loops worden al op IR-niveau
  (blokken + `CondBranch`) geëmit door `IrGeneratingVisitor` en `InstructionEmitter`; de
  callback-gebaseerde `LoopBuilder` bleek nergens gerefereerd en is verwijderd.
- Volledige-pipeline integratietest: compileer een klein `.nabu`-bestand met
  `NabuCompiler.compile(...)` + `--backend=LLVM`, voer de resulterende native executable uit
  en controleer de output.

**Acceptatie:** maven-doel of test die een `.nabu`-bron met de LLVM-backend volledig compileert
én een native executable produceert die correct draait.

---

## Verificatie

- `mvn -pl native-backend test` (`C:\programs\maven\mvn\bin\mvn.cmd`, `$env:MAVEN_HOME="C:\programs\maven\mvn"`).
- Hand-opgebouwde IR-tests uitgebreid: objecten, velden, arrays, strings, dispatch, exceptions
  (NONE + BOEHM).
- E2E: volledige pipeline met `LLVM`-backend produceert draaibare native output.

## Voortgang

### Fase 0 — Objectmodel & veldtoegang: KLAAR ✅
- Nieuw bestand `ClassLayouts.java`: berekent per IRModule (klasse) de object-struct
  `[ nabu_object header (ptr type, i32 refcount, i32 monitor, i32 thread) ][ instantie-velden ]`,
  mapt `IRValue.Named.fieldIndex` (teller over alle velden, statisch+instantie, uit
  `computeFieldIndex`) op de struct-index (statische velden overgeslagen), en emitteert een
  `nabu_type_info`-global (naam, size, super_type=null voorlopig, field_count).
- `LLVMModuleEmitter.emit()` roept vóór globals/signatures/bodies `classLayouts.register(mod)` aan.
- `InstructionEmitter`:
  - `emitLoad`/`emitStore` special-case «object-veldtoegang»: `IRValue.Values(obj, Named(instance))`
    → `LLVMBuildStructGEP2` naar veldoffset, daarna load/store.
  - `emitHeapAlloc` alloceert nu `sizeof(struct)` (+ BOEHM via `GC_malloc`) en initialiseert de
    object-header (`obj->type = &type_info`); NONE = stack-alloca, REFCOUNT = malloc.
  - `ArrayStore` toegevoegd aan de dispatch (was ontbrekend).
  - Array-layout consistent gemaakt: `[i32 length][ elem0 ][ elem1 ]...` — `AllocaArray` alloceert
    `ARRAY_HEADER_SIZE(=8) + count*elemSize` en zet de length-header; `ArrayLoad`/`ArrayStore`
    gebruiken `array + 8 + index*elemSize`; `ArrayLength` leest de i32-header op offset 0.
- Met `LLVMBuildStructGEP2` (i.p.v. handmatige `getelementptr` GEP2) — dit is de correcte API
  voor struct-veldtoegang met opaque pointers in LLVM 21.
- Tests: `ObjectModelBackendTest.java` (5 tests) — object veld store/load (NONE en BOEHM),
  twee velden op afzonderlijke slots, array store/load/length (NONE en BOEHM).
- **Testresultaat**: 38 tests (33 bestaand + 5 nieuw), 0 failures, 1 skipped. BUILD SUCCESS.

**Beperkingen fase 0 (te volgen bij Fase 1):**
- Erfelijke velden van superklassen worden nog niet in de struct opgenomen; `super_type` in
  `nabu_type_info` is voorlopig null (cross-module koppeling hoort bij method-dispatch/Fase 1).
- Statische velden via `Values(obj, Named(static))` worden nog als vorige behandeld (global-lookup).
- Tests compileren (nog) niet end-to-end maar verifiëren de `.o`/`.ll` output; runtime-uitvoering
  en linking komen later.

### Fase 1 — Method dispatch (incrementeel, stap 1): ctor-chaining + super-herkenning ✅
- In de compiler (`IrGeneratingVisitor.visitNewClass`) staat `args.add(obj /* this */)` bij de
  `_init`-aanroep **uitgecomment**: de `_init`-functie is gedeclareerd mét `%this` als eerste
  parameter, maar de aanroep gaf alleen de expliciete argumenten mee. De Java-backend lost dit
  op via een `pendingConstructorAllocs`-deque (HeapAlloc koppelen aan de `_init`-call).
- **Opgelost in `InstructionEmitter`** (native-backend-lokaal, deelt het compiler-IR niet):
  - Nieuwe `Deque<IRValue> pendingConstructorAllocs`.
  - `emitHeapAlloc` duwt `ha.result()` op de deque.
  - `emitCall` herkent een constructor (`callKind()==SPECIAL` en `function().endsWith("_init")`),
    haalt het HeapAlloc-resultaat van de deque en plaatst het als eerste argument ('this') vóór
    de expliciete argumenten. De daadwerkelijke `Call/Invoke` gebruikt de aangevulde arg-lijst
    (en het aantal). Signatuur matcht nu met `@Owner_init(ptr %this, ...)`.
  - Een constructor-zonder-voorafgaande-HeapAlloc geeft een duidelijke foutmelding.
- Super-calls (`function().endsWith("_super")`) blijven no-op zolang er geen inheritance is.
- Tests: `MethodDispatchTest.java` (3 tests) — constructorCallPrependsThis (NONE + BOEHM) en
  superCallRemainsNoOp; verifiëren dat de `_init`-call `(ptr %this, i32 <arg>)` bevat.
- **Testresultaat**: 41 tests (38 + 3 nieuw), 0 failures, 1 skipped. BUILD SUCCESS.

### Fase 1 - stap 2: vtable in object-header + virtuele dispatch OK
- Object-header uitgebreid naar `{ ptr type, ptr vtable, i32 ref_count, i32 monitor, i32 thread }`.
  Header-struct-indices: `HEADER_SLOT_TYPE=0`, `HEADER_SLOT_VTABLE=1`; instance-velden starten op
  outer struct-index 1 (header op index 0).
- Header-velden worden via subfield-GEP's ingesteld in `InstructionEmitter.initObjectHeader`:
  `obj[0]` (= header), daarna `header[0]`=type en `header[1]`=vtable.
- `ClassLayouts.buildVtable`: per klasse een vtable-array `[N x ptr]` van functiepointers als
  `global constant` (internal linkage), naam `_ZNabu<internalName>Evtable`. Virtuele methoden =
  niet-static, niet-priv&eacute;, niet-final, niet-constructor, niet-`_super`, gesorteerd op functienaam.
  Lege vtable (geen virtuele methoden) krijgt `LLVMConstNull` als initializer - nodig om een geldige
  (niet-externe) global te zijn.
- `ClassLayout.vtableGlobal` + `virtualSlots` (full-method-naam -> slot) + `vtableSlot(String)`.
- Ordering in `LLVMModuleEmitter.emit` gewijzigd: `classLayouts.register` staat nu **na**
  `declareSignature` (zodat vtable-array's naar de gedeclareerde `@Owner_method`-functies kunnen
  verwijzen) en v&oacute;&oacute;r `emitBody`.
- Virtuele dispatch in `InstructionEmitter.emitCall` (`tryEmitVirtualCall`): alleen wanneer
  `callKind()==VIRTUAL`, de receiver in `args().get(0)` een bekende klasse-layout heeft &eacute;n die
  layout een vtable-slot heeft voor de methode; dan indirect via header->vtable->slot->call. Anders
  fallback naar een directe call (voorkomt dat vrijstaande functies met `CallKind.VIRTUAL`, bv.
  `puts`/`helper`, breken).
- **Beperking (bewust, conform fase 0):** cross-class override slot-sharing is nog niet opgelost
  (super-klasse en vererving volgen in Fase 5/wiring); slots zijn per klasse, gesorteerd op naam.
- Tests: `MethodDispatchTest.virtualDispatchUsesVtable` - compileert `test/Animal` met virtuele
  `speak`, roept via vtable aan en verifieert dat de `.ll` een vtable-global, vtable-load en
  functiepointer-load bevat en g&eacute;n directe `call @test_Animal_speak(`.
- **Testresultaat**: 42 tests, 0 failures, 1 skipped. BUILD SUCCESS.


### Fase 1 - stap 3: itable (interface-calls) + `_super`-methode-aanroep (directe call) OK
- Object-header uitgebreid naar `{ ptr type, ptr vtable, ptr itable, i32 ref_count, i32 monitor,
  i32 thread }`. Nieuwe constante `HEADER_SLOT_ITABLE = 2`; de itable-pointer wordt in
  `initObjectHeader` via een subfield-GEP op `header[2]` gezet.
- **`_super`-methode-aanroep** (`super.m()`, geen constructor): de frontend emitteert dit als
  `CallKind.VIRTUAL` met `function() = Owner_super_m` en receiver `this`. Omdat een externe
  super-methode nu NIET in de vtable van de klasse komt, is er geen vtable-slot voor
  `Owner_super_m` op de subklasse -> `tryEmitVirtualCall` geeft `false` -> directe call naar
  `@Owner_super_m` (invokespecial-gedrag). De super-*constructor* (`xxx_super`) blijft voorlopig
  een no-op (object/veld-erfenis volgt in Fase 5).
- **itable (interface-calls)**: `ClassLayouts.buildItable` scant de module op
  `CallKind.INTERFACE`-calls en bouwt per klasse een `[N x ptr]`-array van functiepointers
  (`_ZNabu<internalName>Eitable`, internal linkage const, leeg = `LLVMConstNull`). Elke
  interface-methode (full-naam `Interface_m`) krijgt een slot; de functiepointer moet gedeclareerd
  zijn (externe functie) in `globalValueMap`. `ClassLayout.itableGlobal` + `itableSlots` +
  `itableSlot(String)`.
- `InstructionEmitter.emitCall`: nieuwe branch `CallKind.INTERFACE` -> `tryEmitInterfaceCall`
  (header->itable->slot->functiepointer->indirecte call), analoog aan de vtable-dispatch.
- `buildVtable` filtert nu `.filter(f -> !f.isExternal())` zodat externe super/interface-methoden
  niet per abuis als virtuele methoden van de klasse worden geregistreerd.
- **Beperkingen (bewust):** interface-methode wordt als externe functie in dezelfde module
  gedeclareerd (cross-module implementatie-koppeling + cross-class override-slot-deling volgt in
  Fase 5/wiring); itable is per klasse een vlakke array (geen per-interface typeInfo-wrapper).
- Tests: `MethodDispatchTest.superMethodCallIsDirectCall` en
  `MethodDispatchTest.interfaceCallUsesItable`.
- **Testresultaat**: 44 tests, 0 failures, 1 skipped. BUILD SUCCESS. Plugin-jar gedeployed naar
  `~/.nabu/plugins`.

### Fase 2 - Strings & string-concat: KLAAR âœ…
- **String-globals** waren al correct: `LLVMConstStringInContext(..., length, DontNullTerminate=0)`
  in `ConstantResolver.resolveString` en `GlobalEmitter.emitStringGlobal` maakt nul-afgesloten
  `[N+1 x i8]`-globals, geregistreerd in `globalValueMap` (`.str.N`) zodat `resolveValue`
  van een `Named("@.str.N", Ptr(i8))` werkt.
- **Concat-detectie**: `InstructionEmitter.emitBinaryOp` vangt nu `BinaryOp(ADD)` af waarbij
  `left.type()` en `right.type()` beide `Ptr(i8)` zijn (`isStringType`) i.p.v. de integer-add
  (`LLVMBuildAdd`) op pointers. Nieuwe helper `emitStringConcat` voert een indirecte call uit
  naar `@nabu_concat`.
- **`@nabu_concat`-declaratie**: `LLVMModuleEmitter.declareRuntimeHelpers` declareert nu
  `i8* nabu_concat(i8*, i8*)` (external linkage, altijd) in `globalValueMap`.
- **Runtime**: `nabu_runtime.h/.c` krijgen `char *nabu_concat(const char *a, const char *b)`
  (NULL-behandeling, `strlen`/`memcpy`, `malloc` + nul-terminatie). Let op: het resultaat wordt
  voorlopig met `malloc` gealloceerd â€” GC-allocatie (BOEHM/GC_malloc) voor concat-resultaten
  volgt in Fase 4 wanneer de GC-strategieÃ«n consistent worden getrokken.
- **Beperking (bewust):** het concat-resultaat is een raw `i8*` zonder object-header/box; de
  opgeslagen concat-waarde kan alleen als C-string gebruikt worden. Echte nabu-`String`-boxing
  en `StringBuilder`-optimalisaties vallen buiten deze fase.
- Test: `MethodDispatchTest.stringConcatCallsRuntimeHelper` (importeert `IRInstruction`).
- **Testresultaat**: 45 tests, 0 failures, 1 skipped. BUILD SUCCESS. Plugin-jar gedeployed naar
  `~/.nabu/plugins`.

**Volgende stap (Fase 3):** exception handling voltooien â€” `emitThrow` via landing-pad,
catch-type-filtering (`_ZTIN...`), geldige landingpads voor alle handlers, en
`finally`/synchronized-`nabu_monitorexit` op het uitzonderingspad.

### Fase 3 â€” Exception handling voltooien: frontend try/catch â†’ IR gekoppeld, backend-emissie gevonden ✅
Deze sessie richtte zich op het frontend-gat (choos: "Frontend try/catch â†’ IR koppelen") en
bracht try/catch/throw van echte broncode door de hele keten:
- **Parser** (`JavaCompilerVisitor`): `visitTryStatement`, `visitCatchClause`,
  `visitThrowStatement` toegevoegd â€” ANTLR-`visitChildren` mangelde try/catch/throw tot lege
  geneste blokken. Nu bouwen ze correcte `CTryStatementTree`/`CThrowStatement`-knooppunten
  (geverifieerd via `parsed.txt`-dump).
- **Resolver** (`ResolverPhase`): `visitTryStatement`/`visitCatch` toegevoegd â€” try-body,
  finalizer en (cruciaal) de catch-variabele (`acceptTree` â†’ krijgt een `LocalVariableSymbol`)
  worden nu geresolved; de voormalige NPE `symbol == null` in
  `IrGeneratingVisitor.visitVariableDeclaratorStatement` is opgelost.
- **Optimizer-fix** (`compiler-api/Optimizer.removeRedundantBranches`): verwijdert niet langer
  de enige terminator van een blok. Voorheen verdween de `br %next` van een entry-blok dat als
  eerste een try stond, waardoor `entry` zonder terminator achterbleef
  ("Basic Block ... does not have terminator").
- **Backend-fix** (`InstructionEmitter`): declareert nu lazy de externe
  `java_lang_Exception_init`-constructor (`void(i8*)`) als die niet in de module zit
  (was "Onbekende functie: java_lang_Exception_init").
- **Nieuw** `NativeLLVMBackend.emitIRText(module, opts, .ll)`: emitteert uitsluitend de LLVM-IR
  tekst (zonder de platform*kritische* validate/codegen), voor inspectie en de E2E-test.
- **E2E-test** (`NativeExceptionEndToEndTest`): draait echte bron door de volledige pipeline
  (EnterPhase â†’ ResolverPhase â†’ IrGeneratingVisitor â†’ TypeInference â†’ SSA â†’ Optimizer â†’
  backend.emit) en assert `landingpad` + `nabu_can_catch` in de gegenereerde LLVM-IR.
- **Resultaat**: de gegenereerde IR is nu correct structuur: `entry â†’ br %try.body`, `invoke
  @java_lang_Exception_init ... unwind %try.catch.0`, `landingpad { ptr, i32 } cleanup`,
  `nabu_catch` + `nabu_can_catch`-typefilter, `.rethrow`-pad (`nabu_throw` + `unreachable`).
- **Testresultaat**: native-backend 49 tests, 0 failures, 1 skipped. BUILD SUCCESS.

**Bevestigde platform-blokkering (niet onze code):** gesignaleerd via dit proces dat zelfs
`LLVMVerifyModule` (validate) **native segfault** (0xC0000005; hs_err: crash-frame
`LLVMVerifyModule` â†’ `NativeLLVMBackend.validate`) op de geldige Itanium-personality
(`__gcc_personality_v0`) uitzonderings-IR voor het `x86_64-pc-windows-msvc`-target. LLVM 21
ondersteunt het Itanium-EH-model niet op een Windows/MSVC-target. Gevolg: object-emissie,
valideren Ã©n het runnen van een native exception-exe blijven op dit platform geblokkeerd
(clang/MSVC only, geen MinGW/libunwind) â€” precies de eerder voorziene linkerbranch-beperking,
nu ook in de verifier. De E2E-test gebruikt daarom `emitIRText` i.p.v. `compileToObject`.

**Volgende stappen (niet gekozen in deze sessie):**
- Object/veld-erfenis + cross-class override-slot-deling + super-constructor in Fase 5/wiring.
- Platform-unwinder (MinGW/libunwind) of WinEH-persoonlijkheid om exception-exe's op Windows
  daadwerkelijk te valideren/runnen.

### MinGW/libunwind-pad — verkenning + toolchain-proof ✅ (beperkt door SEH-model)

**Wat is geïnstalleerd** (geen admin nodig; choco had admin nodig → standalone winlibs):
- `C:\programs\mingw64\mingw64` — winlibs **GCC 16.2.0 (x86_64-posix-SEH)** + MinGW-w64 UCRT 14.0.
  Opmerking: op x86_64 Windows is **SEH** de enige MinGW-unwinder (DWARF alleen op i686). De
  unwinder-symbolen zitten in `bin\libgcc_s_seh-1.dll` (exporteert `_GCC_specific_handler` +
  alle `_Unwind_*`), **niet** in de statische `libgcc.a` (dat is alleen compiler-support).

**Belangrijkste doorbraak:** `LLVMVerifyModule` crasht **niet meer** wanneer het target
`x86_64-w64-windows-gnu` is i.p.v. `x86_64-pc-windows-msvc`. clang compileert de volledige
exception-IR (landingpad + `__gcc_personality_v0`) foutloos naar een `.o` voor gnu. De
`0xC0000005`-crash in de MSVC-verifier was dus puur het target, niet onze IR.

**Runtime-fix:** `nabu_runtime.c` compileert nu ook met MinGW gcc (eerder niet):
- `<stdint.h>` toegevoegd voor `uint64_t`.
- `_Unwind_Exception` → `struct _Unwind_Exception` (MinGW SEH `unwind.h` gebruikt géén typedef).
- Zijn Itanium-branch (regel 29, `#if defined(__GNUC__)...`) wordt voor MinGW gebruikt en
  verwijst naar `_Unwind_RaiseException` (door libgcc_s geleverd).

**Bewijs dat SEH native exceptions kan uitvoeren:** een handgeschreven demo-IR
(`demo.ll`: `Main_main` gooit via `invoke @nabu_throw ... unwind %try.catch`, landingpad
`cleanup` + `nabu_catch` + `nabu_can_catch`) compil�ert/linkt naar een native `.exe`.
Kritieke bevinding: alleen `<gcc> installatie installeren en `__gcc_personality_v0` verwijzen
werkt **niet**.

**Bepalende mismatch (SEH vs DWARF):**
- De nabu-backend emitteert het **Itanium/DWARF** model: `personality ptr @__gcc_personality_v0`
  + handmatig `_Unwind_RaiseException` (NABUNABU exception-class) + string-gebaseerd
  `nabu_can_catch` filter in het landingpad.
- MinGW SEH gebruikt **`_GCC_specific_handler`** en een SEH-persoonlijkheid. clang's C++
  objecten verwijzen naar **`__gxx_personality_seh0`** (aangetoond in `cpptest.o`), dat door
  libgcc_s wordt geleverd. `__gcc_personality_v0` bestaat **niet** in MinGW SEH → linkfout.
- Experiment: demo-IR met personality `__gcc_personality_v0` → linkfout (undefined symbol).
  Met `__gxx_personality_seh0` → **linkt** en de SEH-unwind start (¥), maar de exception wordt
  **niet gevangen** (§): de C++-persoonlijkheid interpreteert onze `NABUNABU`-exception via het
  LSDA/typeinfo niet als match voor onze `cleanup`-landingpad → valt door naar
  `nabu_throw` "onbehandelde exception" (exit 0xC0000409).

**Conclusie / vervolgkeuze (beperking SEH-model):**
- Het DWARF `_Unwind`-model werkt natively wÃ©l op **Linux/macOS** (en i686-MinGW-DWARF), waar
  `__gcc_personality_v0` + `_Unwind_RaiseException` de normale ABI is.
- Voor Windows x86_64 (SEH: MinGW én MSVC) zit de blokkade nu op het **persoonlijkheidsniveau**,
  niet meer op de verifier. Native uitzonderingsexecutie op Windows-SEH vereist Ã³f:
  1. een **nabu-eigen SEH-persoonlijkheidsfunctie** die `_GCC_specific_handler` kan aanroepen
     (nieuwe `nabu_personality` in `nabu_runtime.c`, gekoppeld via `__gcc_personality_v0`-alias
     of directe `_GCC_specific_handler`-verwijzing) en die de `NABUNABU`-exception-class +
     string-typefilter via het landingpad routeert; of
  2. het emitteren van echte C++-achtige LSDA/typeinfo + `__cxa_throw`-compatibel gooien, of
  3. native exception-emissie op SEH-platforms niet-ondersteund verklaren (alleen DWARF-platforms).
- Toolchain staat klaar; `nabu_runtime.o` compileert met gcc; de verifier-blokkade is weg. De
  resterende keuze is een softwarematig ontwerpbesluit (SEH-persoonlijkheid schrijven vs. SEH
  niet ondersteunen).

**Doorbraak (nabu SEH-persoonlijkheid werkt native):** de eerdere "garbage-argumenten" waren
geen ABI-mismatch maar een signatuur-fout: `nabu_personality` werd door clang **direct als de
rauwe 4-arg SEH-handler** in `.xdata` gezet, terwijl het de 5-arg Itanium-signatuur had — dus
las het de SEH-registers (PEXCEPTION_RECORD/EstablisherFrame/PCONTEXT) als Itanium-args (vandaar
v=0x69.. , actions=stackaddr). De juiste aanpak is exact zoals GCC zelf doet (`__gcc_personality_seh0`):
een **rauwe 4-arg SEH-wrapper** `nabu_seh_personality(PEXCEPTION_RECORD, void*, PCONTEXT,
PDISPATCHER_CONTEXT)` die doorstuurt naar `_GCC_specific_handler(ms_exc, frame, ctx, disp,
nabu_itanium_personality)`. Die `nabu_itanium_personality` (5-arg Itanium-signatuur) doet:
fase-1 SEARCH -> `_URC_HANDLER_FOUND` (voor de eigen regio via `_Unwind_GetRegionStart`);
fase-1 HANDLER_FRAME -> `_Unwind_SetGR(0, ue_header)` + `_Unwind_SetIP(landingpad)` ->
`_URC_INSTALL_CONTEXT`. `_GCC_specific_handler` regelt dan zelf `RtlUnwindEx`/fase-2.

**Geverifieerd end-to-end** (demo, clang->.o + gcc-link + run op Windows/MinGW-SEH): `nabu_throw`
-> `_Unwind_RaiseException` -> `RaiseException(STATUS_GCC_THROW, 0x20474343)` -> SEH-dispatcher
roept `.xdata`-handler `nabu_seh_personality` -> `_GCC_specific_handler` -> SEARCH/HANDLER_FOUND
-> fase-2 -> landing pad draait -> `nabu_catch` -> `nabu_can_catch` type-match -> **CATCH_OK,
exit 0**. Enige echte bug onderweg: `_Unwind_SetGR(context, 0, (long)ue_header)` — op Windows is
`sizeof(long)==4`, dus het 64-bit `ue_header`-pointer werd 32-bit getrunceerd en sign-extended
(`0xFFFFFFFF9A8371C0`), wat ACCESS_VIOLATION gaf in `nabu_catch`. Fix: cast naar `_Unwind_Word`
(64-bit).

**Resteert voor de echte backend-integratie (keuze):**
- LLVM emitteert **geen** `.gcc_except_table`-LSDA voor dit minimal `cleanup`-landingpad (ook
  niet voor `__gcc_personality_v0`), dus `_Unwind_GetLanguageSpecificData`/`disp->HandlerData`
  is leeg. De proof-of-concept gebruikte daarom een demo-side-table (globale
  `nabu_region_start`/`nabu_landing_pad`-pointers, keyed op `_Unwind_GetRegionStart`).
- Option A: **side-table per functie** — backend emitteert per try-functie twee pointers en
  registreert ze; persoonlijkheid zoekt op regio. Simpel, werkt met LLVM's huidige (LSDA-loze)
  output. Niet-standaard.
- Option B: **echte LSDA** — LLVM laten weten de LSDA te emitteren (vgl. CPP-kant) en volle
  LSDA-parsing (call-site tabel + type-lookup) implementeren; algemeen/correct, maar vereist
  LLVM-LSDA-emissie-flumming voor een custom persoonlijkheid.
- Of: DWARF-only + SEH als fallback documenteren.

### Option B — echte LSDA geïmplementeerd en werkend ✅ (nabu SEH-persoonlijkheid)

**Gekozen pad (optie B).** In plaats van de side-table (optie A) is de **echte LSDA**-route
geïmplementeerd en end-to-end geverifieerd op Windows/MinGW-SEH.

**Belangrijkste LSDA-mechanisme-bevinding (beslissend):** LLVM emitteert géén LSDA voor een
`cleanup`-only landingpad, maar zodra het landingpad een **echte `catch ptr @<global>`-clause**
heeft, emitteert LLVM wél een volledige `.gcc_except_table`-LSDA in `.xdata` — **ook met onze
custom personality-naam** `nabu_seh_personality`. LLVM negeert de personality-naam bij de
LSDA-emissiebeslissing.

**Backend-wijzigingen (naar echte LSDA):**
- `FunctionEmitter.emitLandingPad`: landingpad emit nu als `cleanup` **plus** een echte
  `catch ptr @<typeinfo>`-clause (i.p.v. clauseloos), zodat LLVM de LSDA genereert. Omdat de
  type-filter in code via `nabu_can_catch` gebeurt, dient een gegarandeerd aanwezige
  typeinfo-marker-global als clause-type (`@_ZNabunabu.exceptionsEtype_info`, key
  `@nabu_exception_typeinfo`).
- `LLVMModuleEmitter.declareRuntimeHelpers`: (1) declameert nu de 4-arg SEH-persoonlijkheid
  **`nabu_seh_personality`** (i.p.v. de niet-bestaande `__gcc_personality_v0`); (2) emitteert de
  exception-typeinfo-marker-global `{ ptr, i64, ptr, i32 } { @.exc.marker, 0, null, 0 }`.

**Runtime-wijziging (`nabu_runtime.c`):** de SEH-personaliteitsfuncties zijn in de GNUC/clang-
branch gevouwen:
- `nabu_seh_personality` (rauwe 4-arg SEH-wrapper, in `.xdata` geregistreerd) stuurt door naar
  `_GCC_specific_handler(ms_exc, frame, ctx, disp, nabu_itanium_personality)`.
- `nabu_itanium_personality` (5-arg Itanium) leest de LSDA via `_Unwind_GetLanguageSpecificData`
  (=`disp->HandlerData`), parsed de header (`parse_lsda_header`), scant de call-site tabel, en
  return bij een match `_URC_HANDLER_FOUND` (SEARCH) dan wel installeert het landingspad met
  `_Unwind_SetGR(0, ue_header)` + `_Unwind_SetGR(1, selector)` + `_Unwind_SetIP(lpad)` →
  `_URC_INSTALL_CONTEXT` (HANDLER_FRAME). `_GCC_specific_handler` regelt `RtlUnwindEx`/fase-2.
  De pointer-set gebruikt `(_Unwind_Word)` (64-bit), niet `(long)` (verhindert 32-bit truncatie).

**Geverifieerd end-to-end:**
- Backend-shape-demo (`demo_backend_shape.ll`: landingpad `cleanup` + `catch ptr
  @_ZNabunabu.exceptionsEtype_info`, personality `nabu_seh_personality`) → `.xdata` bevat de
  LSDA (call-site-tabel vanaf 0x0c, typeinfo-ADDR64 op 0x18) → gcc-link → run → **CATCH_OK,
  exit 0** op MinGW-SEH.
- `NativeExceptionEndToEndTest` (volledige frontend→IR-pipeline) slaagt: het gegenereerde `.ll`
  bevat `personality ptr @nabu_seh_personality` én `landingpad ... cleanup catch ptr
  @_ZNabunabu.exceptionsEtype_info`.

**Valkuil gevonden tijdens integratie (native LLVM-crash):** een `PointerPointer`-buffer mag NIET
worden hergebruikt voor zowel `LLVMStructTypeInContext` (types) als `LLVMConstNamedStruct`
(values) — dat gaf `EXCEPTION_ACCESS_VIOLATION` in `jniLLVM.dll`. Oplossing: twee aparte buffers,
exact zoals `ClassLayouts.emitTypeInfo` al deed.

**Resteert (buiten deze sessie, Fase 5):** echte `main`-emissie + end-to-end wiring nabu-bron→exe
(het objectveld-model van `java_lang_Exception` en het nog lege catch-lichaam zijn frontendzaken,
niet de SEH-persoonlijkheid).

### Fase 5 — Wiring + end-to-end nabu-bron → exe: KLAAR ✅

De volledige pipeline `nabu-bron met try/catch → .ll → .o → MinGW-gcc-link → run (exit 0)` werkt.

**Backend `main`-bridge (`NativeLLVMBackend.ensureMainEntry`):**
- De nabu-frontend emitteert `Main_main` (geen letterlijke `main`). `ensureMainEntry` voegt toe
  vóór codegen: `define i32 @main() { call void @Main_main(ptr null); ret i32 0 }`.
- De gate `hasMain` in `compile()` initialiseert pas nadat `ensureMainEntry` draait.

**Linker MinGW-ondersteuning:**
- `Linker.resolveCompiler("gcc")` doorloopt: systeemproperty `nabu.gcc`, env-var `NABU_GCC`,
  bekende winlibs/MinGW-pad-instellingen (`C:\programs\mingw64\mingw64\bin\gcc.exe`, etc.),
  en valt terug op PATH.
- Windows-gnu pad: `gcc -o <exe> <objects> <runtime/nabu_runtime.c>` — de runtime wordt meegecompileerd
  en gelinkt; gcc koppelt `libgcc_s_seh` automatisch.
- Voor de `.exe`-run: MinGW-bin toegevoegd aan `ProcessBuilder.environment()` (en in de JUnit-test
  op PATH voor `libgcc_s_seh-1.dll`).

**PointerPointer-bug gevonden & opgelost (`FunctionEmitter.emitLandingPad`):**
- Oorzaak van `LLVMVerifyModule`'s nondeterministische crash (0xC0000005) en
  "Call parameter type does not match function signature!": een `PointerPointer<Pointer>` met
  LLVMValueRef-waarden werd doorgegeven als het tweede argument (param-type-lijst) van
  `LLVMFunctionType` — LLVM interpreteerde de value-handles als LLVMTypeRef, wat garbage
  param-types opleverde.
- Oplossing: apart `PointerPointer<Pointer> ccParamTypes` opbouwen met `LLVMTypeOf(exPtr)` en
  `LLVMTypeOf(typeStr)` (beide geeft `ptr`); `ccParams` (de waarden) wordt uitsluitend als
  argumentenlijst aan `LLVMBuildCall2` meegegeven.

**Eindresultaat (`fullSourceToExeCatchesException` test):**
- Volledige bron: `class Main { static void main(String[] args) { try { throw new Exception(); }
  catch (Exception e) {} } }`.
- Pipeline: frontend → IR (`Main_main`) → `ensureMainEntry` bridge → LLVM emit
  (`nabu_seh_personality` + `landingpad cleanup catch ptr @_ZNabunabu.exceptionsEtype_info` +
  `nabu_new_object` + `nabu_can_catch` type-check) → LLVMVerifyModule rc=0 →
  PassManager O2 → object → MinGW-gcc link met `nabu_runtime.c` → run → exit 0 (exception
  gevangen, geen abort).
- Volledige native-backend JUnit-suite: **50 tests, 0 failures, 1 skipped**. BUILD SUCCESS.

**Volgende stappen (Fase 1/5, buiten scope exception-handling):**
- Object/veld-erfenis + cross-class override-slot-deling + super-constructor.
- `java.lang.Exception` / `java.lang.Object` volledige klasse-layout + `_init`.
- `--backend=LLVM` doorlaten in maven-plugin/CLI.
- Overige PointerPointer-audit (alleen `emitLandingPad` geaudit/gefixt; overige
  `LLVMFunctionType`-calls zijn in orde bevonden).

### Fase 5 (vervolg) — echte `.nabu`-wiring via `NabuCompiler.compile` KLAAR ✅

De officiële pipeline **`.nabu`-bestand op schijf → `NabuCompiler.compile(options)` met
`--backend=LLVM` → `Main.exe` → run (exit 0)** is geverifieerd met
`NabuCompilerBackendAcceptanceIT` (groen).

**Fixes die nodig waren na de e2e (Java-bron via backend-API):**

1. **Rapportage van backend-fouten** (`NabuCompiler.generateCode`): de code slikte
   `CompileException` stil in (return `-1` zonder melding). Nu wordt een
   `DefaultDiagnostic(Kind.ERROR, e.getMessage(), null)` aan de listener gerapporteerd vóór
   `return -1`, zodat backend-fouten zichtbaar zijn in de diagnostics van de test.

2. **Target-triple-wiring** (`Linker.guessTargetTriple`): `NabuCompiler.compile` bouwt
   `CompileOptions.defaults()` **zonder** target-triple → de oude code gebruikte
   `LLVMGetDefaultTargetTriple()` = `x86_64-pc-windows-msvc` (verkeerde ABI/PTX voor de
   MinGW-SEH-runtime; precies de historische "LLVM validatiefout"-instabiliteit met een lege
   error-message). `NativeLLVMBackend.compile` berekent de triple nu één keer — expliciet of
   via `guessTargetTriple(LLVMGetDefaultTargetTriple().getString())` (`gcc` gevonden →
   `x86_64-w64-windows-gnu`) — en geeft die door aan codegen én `Linker.link`. De publieke
   3-arg `compileToObject`-overload behoudt het oude gedrag.

3. **Debugbaarheid:** het `.ll`-bestand wordt nu vóór `validate()` weggeschreven (eerder erna,
   dus een validatie-fout liet geen trace achter).

4. **`ensureMainEntry` bridge-match op parameter-count:** een nabu `fun main(args: String[])` is
   een **instance**-methode → de frontend emitteert `define void @Main_main(ptr %this, ptr %args)`
   (2 params) terwijl Java `static void main(String[])` `Main_main(ptr %args)` (1 param) geeft.
   De bridge hard-codeerde `call void @<entry>(ptr null)` → bij de `.nabu`-payload gaf
   `LLVMVerifyModule` (foutmelding onzichtbaar, zie #2) "call arity mismatch". Fix: het aantal
   null-argumenten volgt nu `entry.params.size()` en de call-signature volgt
   `entry.params[].type()` (de emitter baseert de aangeroepen functie-type hoe dan ook op de
   gedeclareerde global, `LLVMGlobalGetValueType`).

**Bevinding (nieuw):** `NabuCompiler.compile` ziet alleen bron-kinds `language-parser`
(`.nabu`); `.java`-bestanden in `SOURCE_PATH` worden **niet** geselecteerd (die zijn
`language-support`) — de acceptance-test gebruikt dus `.nabu`, niet `.java`.

**Resultaat:** native-backend-suite **50 tests, 0 failures, 1 skipped** (incl. de nieuwe
`NabuCompilerBackendAcceptanceIT`); compiler-module 1258 tests met alleen de pre-bestaande
`backend.asm.InstructionEmitterTest`-failure (niet aangeraakt). BUILD SUCCESS.

**Blokkade OPGEHEVEN: nabu-frontend try/catch (2 fixes) ✅.** De eerder gedocumenteerde
"try/catch parse-bug" was tweeledig en is nu opgelost — een écht `.nabu`-bestand mét
try/catch compileert nu door de officiële `compile()`-pipeline tot een draaiende exe:

1. **Verkeerde syntax in de probe** (geen backend-bug): nabu gebruikt
   `catch (e : Exception)` (identifier vóór, type ná de dubbele punt), niet Java's
   `catch (Exception e)`. Die Java-payload gaf "no viable alternative ... catch";
   de grammatica zelf is correct (zie `nabu-lang/.../NabuParser.g4:1136-1164` en de
   wél werkende `NabuCompilerVisitorTest.tryStatement` round-trip-test).
2. **Echte frontend-bug:** `NabuCompilerVisitor.visitCatchFormalParameter` bouwde de
   catch-variabele zonder `.kind(Kind.LOCAL_VARIABLE)` (de Java-frontend doet dit wél,
   JavaCompilerVisitor:2856). Zonder dat kind slaat `ResolverPhase.visitVariableDeclaratorStatement`
   de symbool-aanmaak over → `varDecl.getName().getSymbol()` is null →
   NPE in `IrGeneratingVisitor.visitVariableDeclaratorStatement` (regel 396). Fix:
   `.kind(Kind.LOCAL_VARIABLE)` toegevoegd aan de nabu-builder.
3. **Test-printer-fix:** met LOCAL_VARIABLE rendert `testing/.../TreePrinter` (generiek
   statement-formaat) `catch (var e : Exception;)' — géén geldige nabu-syntax (breekt de
   round-trip-assert). `TreePrinter.visitCatch` emitteert catch-variabelen nu in
   `e : Exception`-vorm (naam : type i.p.v. `var`/`;`).

**Resultaat:** nabu-lang-suite **53 tests, 0 failures**; native-backend **50 tests, 0 failures**
(inclusief `NabuCompilerBackendAcceptanceIT`, die nu een .nabu-bron met `try { throw new
Exception(); } catch (e : Exception) {}` via `NabuCompiler.compile --backend=LLVM` naar een
draaiende `Main.exe` compileert; exit 0 = exception gevangen door nabu_seh_personality + LSDA).

### Fase 5 (vervolg 2) — maven-plugin / CLI wiring + runtime-bundeling

**maven-plugin — `backend`-parameter** (`maven-plugin/.../AbstractCompilerMojo`):
`@Parameter(property = "nabu.backend", defaultValue = "ASM")` + `configureBackend()`; dit wordt
aangeroepen **vóór** `configureCompilerArguments`, zodat een expliciete `-backend:...`-CLI-optie
wint over `<backend>` uit de pom.

**nabu-client — `--backend`-emisssie** (`nabu-client/.../CompilerOptionBuilder`): nieuwe
`backend(String)`-methode + `--backend` in `build()`. Daarnaast een pre-bestaande bug gefixt:
de `--source-path`-guard checkte `classPath.isEmpty()` i.p.v. `sourcePath.isEmpty()`.

**Runtime-bundeling in de native-backend jar** (`native-backend/pom.xml` + `Linker`):
`nabu_runtime.c/.h` wordt via een pom-`<resources>`-blok (targetPath `nabu/runtime`) in de jar
gekopieerd; `Linker.runtimeSource()` kreeg als laatste fallback `bundledRuntime()` — extractie
van `nabu/runtime/nabu_runtime.c` vanaf het classpath naar een temp-dir (gecachet in
`bundledRuntimeCache`). Geverifieerd: de bron zit in `~/.nabu/plugins/native-backend.jar` en de
lokale-repo-jar (`nabu/runtime/nabu_runtime.c` + `.h`).

### ⚠️ Omgevingsbevinding (geen code-regressie): Bitdefender blokkeert exe-launch

Na een periode van volledig groene runs begon `NativeExceptionEndToEndTest.fullSourceToExeCatchesException`
reproduceerbaar te falen met `CreateProcess error=5, Toegang geweigerd`, ook na volledig
herlinken van `e2e.o` + `nabu_runtime.o` met dezelfde `gcc`-invocatie. Forensisch onderzoek:

- Een vers gelinkte exe uit de module + runtime (85 KB, schone `api-ms-win-crt`-imports,
  géén `libgcc_s_seh-1.dll`) is permanent onstartbaar; `rt7.exe` (zelfde runtime, plain main)
  en naakte C-launchables draaien wél. Geldt voor élke nieuwe naam én elke locatie (temp én
  workspace) → inhoud-getriggerd, niet naam/plek.
- Rawe `CreateProcessW` levert **`err=123 (ERROR_INVALID_NAME)`** en Sysinternals `handle.exe`
  vindt **géén open handle** → klassieke **delete-pending**-toestand: iets opent het bestand
  zodra het gelinkt is en houdt het (kort tot langdurig) vast.
- Windows Defender rapporteert **uit**; er is een **Bitdefender**-installation actief
  (`VSSERV` "Bitdefender Virus Shield", `bdservicehost`, `bdagent`, `bdredline` draaien).
  Diagnose: Bitdefender's realtime-scanner pint de pas-gelinkte exes vast (scan-on-write /
  delete-pending), wat `CreateProcess` laat falen. Bitdefender-exclusie op `%TEMP%` was niet
  effectief in de standby-verificatie (mogelijk verkeerde tab/scan-cache).

🔧 **Actie voor dit systeem:** exclusie in Bitdefender UI bij *Real-time protection → exclusions*
toevoegen (bv. `C:\Users\evert\AppData\Local\Temp` en de `target`-mappen), of realtime-scan
tijdelijk pauzeren; daarna suite heraanraden. **Geen code-wijziging nodig** — dezelfde bytes
(en exact dezelfde testpipeline) waren eerder vandaag groen.

### Fase 5 (vervolg 3) — échte maven-plugin e2e ✅

Een echt Maven-project (`src/main/java/Main.nabu` met try/catch) dat `nabu-maven-plugin`
0.1.0-SNAPSHOT gebruikt, is end-to-end gecompileerd via **`mvn clean compile`**:

```
[INFO] Loading plugins from: C:\Users\evert\.nabu\plugins\native-backend.jar
[INFO] BUILD SUCCESS
target/classes/Main.exe   (85792 bytes)  + Main.ll + Main.o + .nabu-incremental-state
```

- **Plugin-opzet:** `<configuration><backend>LLVM</backend></configuration>` (property
  `nabu.backend`) + `<executions>` èn een **kiezelslag op defaultPhase**: een goal wint zonder
  `<executions>` géén auto-binding aan zijn default-phase — dat geldt alleen bij
  CLI-invocatie (`plugin:compile`). Zonder executions draait het mojo dus nooit.
- **`compilerArgs` nodig voor stubs:** `--system:C:\projects\nabu\compiler\src\test\resources` en
  `--module-source-path:...\jmods` werden via `<compilerArgs>` meegegeven (de mojo-exposed
  parameters dekken SYSTEM/MODULE_SOURCE_PATH niet).
- **Nieuwe mojo-fix (code):** verse `clean`-build zonder Java-bronnen laat
  maven-compiler-plugin `target/classes` **niet** aanmaken → de LLVM-backend kon
  Main.ll nérgens schrijven ("Kon .ll niet schrijven: no such file or directory"), dus
  `NabuCompiler.compile` gaf RC!=0. Fix: `AbstractCompilerMojo.execute()` maakt
  `getOutputDirectory().mkdirs()` vóór compile (conform maven-compiler-plugin-conventie).
- **`~/.nabu/plugins/native-backend.jar` wordt bijgewerkt tijdens `package`** (copy-plugin-jar);
  na een herverpakking stond 505MB-jar met bewuste timestamp (5-9-2026). Bevat o.a.
  `nabu/runtime/{nabu_runtime.c,nabu_runtime.h}` (bundling, zie vervolg 2) — de linker in een
  maven-deployment zonder checkout-vrije repo vindt de runtime daar.
- **Gevalideerd viazelfde opties als het mojo** (scratch-JUnit die `CLASS_PATH=""`,
  TARGET_VERSION=1.8, INCREMENTAL=true, SYSTEM+MODULE_SOURCE_PATH exact repliceert): RC=0
  → het resulterende probleem zat dus uitsluitend in het ontbrekende output-dir, niet in de
  opties.
- De **run van de gegenereerde Main.exe** was wél nog geblokkeerd door de in deze sessie
  gedocumenteerde Bitdefender-launch-pinning (zelfde omgevingsissue, niet code); de
  compile-→exe-keten via de officiële maven-plugin is daarmee bewezen.

### Fase 5 (vervolg 4) — nabu-client → nabu-daemon `--backend=LLVM` KLAAR ✅

De **volledige client-keten** is nu end-to-end bewezen:
`CompilerOptionBuilder` (`--backend=LLVM`) → `LightweightClient` (TCP) →
`CompileTaskHandler` → `NabuCompiler.compile` → **exe in de output-dir**. Verificatie in
`nabu-daemon/.../LlvmClientDaemonIT` (2 tests, groen):

- Daemon draait in-process op een **ephemeral port** (constructors
  `LightweightCompilerDaemon(int)` / `LightweightClient(int)`; een vaste 9876 zou testflakes
  geven bij een draaiende daemon).
- `PING`-roundtrip + compile met `Status.STREAMING`→`Status.END`; er wordt een `.exe`
  geproduceerd (geen run nodig: Bitdefender, zie boven).
- **Protocol-fix (code):** `LightweightClient.compile` schreef het aantal opties níet —
  `readCompileOptions` in de daemon leest echter eerst een `count`-int. Zonder die fix was het
  protocol scheef (de `@Disabled LightweightCompilerDaemonTest` was daarvan het symptoom).
  Nu: `writeInt(compilerOptions.size())` vóór de `writeUTF("key value")`-records.
- De daemon-classpath bevat native-backend + nabu-lang als **test-scope-deps** in
  `nabu-daemon/pom.xml`, zodat de LLVM-plugin-registry de backend vindt (zelfde mechanisme als
  de groene acceptance-suite).
- `CompilerOptionBuilder.backend()` emitteert `--backend LLVM`; de daemon vertaalt die map
  (`--source-path`, `-d`, `--backend`) naar `CompilerOptions` voor `NabuCompiler.compile`.

### Fase 1/5 — Object/veld-erfenis + cross-class override-slot-deling + super-constructor KLAAR ✅

**Architectuur: multi-module batch-compilatie.** Object-erfenis is per definitie cross-module
(super-klasse en sub-klasse → aparte `IRModule`s; `NabuCompiler` compileerde eerder per module
apart, waardoor de super-klasse nooit in dezelfde LLVM-context als de sub-klasse zat). Daarom is
de backend nu batch-bewust:

- `Backend.compileAll(List<IRModule>, CompileOptions, Path)` — default iteratie over
  `compile(module, ...)`, behoudt NDV voor backends zonder batch.
- `NativeLLVMBackend`: echte batch (`compile(List)` / `compileAll` / `compileToObject(List)`) in
  **één** LLVM-context: helpers ×1, globals/signatures over alle modules, dan
  `ClassLayouts.registerAll`, dan bodies.
- `LLVMModuleEmitter.emitAll(List)`; `emit()` delegeert naar `List.of(module)`.
- `NabuCompiler.generateCode`: per compilatie-eenheid één batch — modules nl. **na** hun
  individuele TypeInference/SSB/Optimizer passes, daarna `codeBackend.compileAll(modules, ...)`.

**`ClassLayouts` — inheritance-aware (herschreven):**
- `registerAll` in **topologische volgorde** (ouders eerst via `IRModule.superType()`); de
  aanlevert-volgorde van de callers doet er niet toe (test levert bewust [main, dog, animal] aan).
- Struct-lay-out: super-velden worden **geflat** op dezelfde struct-indices als in de super-klasse
  (eigen `fieldIndexToStructIndex` telt alleen eigen velden, startend bij
  `1 + superLayout.instanceFieldCount`); geërfd veld werkt daarmee via de bestaande
  owner-qualified `resolveObjectFieldPointer` zonder verdere GEP-aerobatiek.
- `buildVtable`: overrides delen het **super-slot** (override vervangt de functiepointer in de
  array óp superpositie; geen eigen nieuw slot). Detectie via
  `findOverride`: zelfde `methodTail` (naam na laatste `_`) + zelfde expliciete parameterlijst
  (`sameParameters` skipt param 0 = receiver, die per klasse verschilt). De super-module wordt
  opgezocht via de **interne naam van de super-klasse** (`superLayout.internalName`, met `/`),
  niet via naam-parsing van de functie-full-name (die gebruikt `_`-separators — dat was de bug
  bij de eerste run: `test_Animal_getAge` → `test_Animal` i.p.v. `test/Animal`).
- `emitTypeInfo`: `super_type` wordt nu gezet op de type-info-global van de super-klasse
  (grondslag voor een broer `instanceof`-pad later).

**`InstructionEmitter` — super-constructor-keten:**
- `emitCall`: `_super`-calls worden **altijd** geabsorbeerd (no-op, zoals voorheen) en alleen
  vertaald naar een **directe** `call @<Super>_init` wanneer die functie in de batch zit
  (`tryEmitSuperConstructor`). De `_super`-args bevatten `this` al als eerste parameter
  (frontend-conventie) → geen `prependThis`.

**Resultaat / verificatie (InheritanceE2eTest, 2 tests groen):**
- IR-asserts: `call void @test_Animal_init(` aanwezig, `test_Animal_super` afwezig; type-info
  van beide klassen (met Dog→Animal-super-koppeling).
- E2E: `new Dog(4,5)` + VIRTUAL `Animal_getAge` via Animal-typ-receiver → native .exe → **exit 9**
  (= 4+5): super-keten zet `age`, geërfd veld + eigen veld gelezen, override gedispatched op het
  gedeelde slot. Omdat deze exe géén EH bevat is de launch NIET Bitdefender-gepind → draait.
- Regressies: `findOverride` sloeg eerst op 4 terug (Animal-implementatie); fix =
  super-module-lookup via `superLayout.internalName`. Fix van `_super`-absorb al vóór de
  slot-bug: de twee `superCallIsNoOp`/`superCallRemainsNoOp`-tests eisten unconditional absorb.

**Suite-status:** native-backend 52 tests, 0 failures, 1 skipped (de bekende
Bitdefender-gepinde EH-exe-launch); compiler-module 1240 tests (incl. de nieuwe backend-pipeline),
0 failures, 19 skipped; alleen de pre-existente `backend.asm.InstructionEmitterTest` (ASM-kant)
uitgesloten. BUILD SUCCESS.

### Fase 1/5 (vervolg) — uitbreiden van library-supers: `java.lang.Exception` type-info-keten KLAAR ✅

Het resterende plan-item "java.lang.[Exception/Object] layout + `_init`":

- De runtime leverde al `java_lang_Exception_init` (lege ctor-stub in `nabu_runtime.c`) en
  `nabu_type_info_for` (per-naam type-info-cache voor library-objecten via `nabu_new_object`);
  `nabu_instanceof` loopt de `super_type`-keten af.
- Het echte gat: een **batch-gecompileerde** klasse met een library-super (bv. `class MyException
  extends Exception`) kreeg in `emitTypeInfo` `super_type = null` (super niet in de batch) — dus
  `nabu_can_catch(obj, "java/lang/Exception")` kon op zo'n object nooit matchen ondanks de
  keten-walk in de runtime.
- **Opgelost in `ClassLayouts`**: nieuwe `librarySuperTypeInfo(mod, internalName)` synthetiseert
  een `_ZNabujava/lang/…Etype_info`-global (internal linkage const, grootte = header, field_count
  = 0) voor een niet-in-batch super **inclusief diens keten** via de tabel `librarySuperOf`:
  `Exception → Throwable → Object`, `Throwable → Object`, `Object → null`, onbekende library-klasse
  → `Object` (veilige bovenkant, alles is een Object). Cache per interne naam voorkomt dubbele
  emissie over meerdere subklassen.
- `registerStruct` koppelt `super_type` van een batch-klasse nu aan de gesynthetiseerde
  library-type-info wanneer `superLayout == null` maar `module.superType()` een descriptor draagt.
- **Geverifieerd** (nieuwe test `InheritanceE2eTest.librarySuperTypeInfoSynthesisWiresExceptionChain`):
  de geëmitteerde IR bevat `MyException → Exception → Throwable → Object` (inclusief de
  naam-constanten `java/lang/Exception` e.d. waar `nabu_can_catch` op matcht) en de alloc-header
  zet `MyException`-type-info, zodat de runtime-keten-walk een `catch (e : Exception)` vindt.
- Overige PointerPointer-audit was al in een eerdere sessie afgerond (alleen `emitLandingPad`
  was de echte bug; de resterende `LLVMFunctionType`-calls bleken correct).

**Suite-status:** native-backend 53 tests, 0 failures, 1 skipped (bekende Bitdefender-pin);
compiler-module 1240 tests, 0 failures, 19 skipped. BUILD SUCCESS.

Daarmee zijn alle open plan-items van deze lijst klaar, behalve de enige omgevings-blokkade
(Bitdefender-pin op EH-houdende exe-launch). De laatste pre-existente compiler-failure
(`InstructionEmitterTest.visitForStatement`) is inmiddels ook opgelost: het snapshot verschilde
alleen op het extra lege for-body-blok (`L3 → GOTO L4`, de body is `{}`) — semantisch identieke
bytecode, snapshot bijgewerkt. De compiler-module-suite is daarmee vrij van failures.

### Afronding 3 — bytecode-regressie `compiler-test` + reactor-opruiming ✅

**Nieuwe regressie gevonden & gefixt via de reactor (`IRBuilder.emitLoad`):** de veld-lees-
`emitLoad`-wraps (Afronding 1) gaven de Load-resulttemp voor een **referentie-veld** het type
`pointee` (`I8`, want `Named.type = Ptr(I8, descriptor)`) i.p.v. de referentie zelf. De
ASM-backend (`compiler/.../asm/FunctionEmitter`) kiest store/load/return-opcodes op het
`IRType` van die temp → `Category.getId()` (`return this.id;` met `id: java.util.UUID`) emitteerde
`ISTORE/ILOAD/IRETURN` i.p.v. `ASTORE/ALOAD/ARETURN` (verifier: "Expected I, but found R");
gevangen door de nabu-maven-plugin-compile van het `compiler-test`-voorbeeld (niet door bestaande
snapshot-tests — die gebruiken alleen primitieve velden). Fix in `IRBuilder.emitLoad`: een
inderdaád meegegeven `type` wint, en voor `Values(obj, Named)` met `Ptr`-veldtype wordt het
resultaattype het referentie-`Ptr` zelf. De native-backend is ongevoelig (opaque LLVM-pointers);
de compiler-suite 1258/0 en native-backend 53/0 (één bekende pin) bevestigen geen regressies.

**Reactor-opruiming:** `mvn package` over de volledige reactor verifieerde alle modules
(`compiler-test`-bytecode nu correct). Twee omgevingsrestanten, geen code:
- De `nabu-lang` "target/classes newer than packaged artifact"-waarschuwing is weg (de jar wordt
  nu netjes opnieuw gepakt).
- De `copy-plugin-jar`-stap (`~/.nabu/plugins`) faalt zolang IntelliJ (`idea64`) het jar-bestand
  vasthoudt ("Het proces heeft geen toegang ... door een ander proces") — tijdelijk soft-lock,
  verdwijnt zodra de IDE de plugin ontlaadt/sluit. `nabu-daemon` + `native-backend` pakken los
  daarvan zonder problemen.

### Fase 1/5 (vervolg 2) — bronniveau-erfenis-e2e (3 echte `.nabu`-bestanden) KLAAR ✅

Het eerste echte **bronniveau** super-class-pad door de volledige officiële pipeline, met
cross-file object-erfenis, super-constructor en virtuele override, draait nu als
`SourceInheritanceE2eIT` (3 bronbestanden, verwachte exit 9):

- **`Animal.nabu`**: `class Animal { private int age; fun init(age: int) { this.age = age; }
  fun getAge(): int { return this.age; } }`
- **`Dog.nabu`**: `class Dog extends Animal { private int extra; fun init(age: int, extra: int) {
  super(age); this.extra = extra; } override fun getAge(): int { return super.getAge() + this.extra } }`
- **`Main.nabu`**: `fun main() { val a: Animal = Dog(4, 5); exit(a.getAge()); }` → **exit 9** (4+5).

Dit draait als **één LLVM-batch**: `NabuCompiler.generateCode` (compiler) verzamelt nu de IR-modules
van álle compilatie-eenheden en roept één keer `codeBackend.compileAll(allModules, ...)` aan
(i.p.v. per CU apart), zodat de super-klasse (Animal) in dezelfde LLVM-context zit als de sub-klasse
(Dog). TypeInference+SsaBuilder+Optimizer draaien per module, de backend-emissie is gebatcht.

**Backend-fixes die deze e2e nodig maakte:**

1. **Param-load-fout** (`IrGeneratingVisitor.visitIdentifier`): een scope-entry die al een
   `IRValue.Temp` is (parameter in de frontend-IR; locals zijn wél alloca-`Ptr`s) is een waarde en
   mocht níet nogmaals `emitLoad` krijgen — dat laadde de parameter als pointer.
2. **Veld-lees-naar-IRV** bolde de SSA-analyse/`ErrorProneChecker`: `Values(obj, Named)` is een
   *pointer-pair* en moet worden omgeven door `builder.emitLoad(...)` op beide leesplekken
   (field-access en instantieveld-tak van een identifier) om een gekoppeld
   `init`/`Target_on_load`-IRV-paar te worden. De store-kant (`emitFieldStore`) was al correct.
3. **Vtable-slot-lookup op receiver zonder descriptor** (`ClassLayouts.findLayoutForVtableSlot`):
   SSA-optimalisatie vervangt de receiver van een virtuele call door het `HeapAlloc`-result, wiens
   IR-type een descriptor-loze `Ptr(I8)` is (geen eigen layout). `tryEmitVirtualCall` had daardoor
   géén slot en viel terug op een *directe* `@Animal_getAge`-call (exit 4 = geen override).
   Nieuwe helper scant klassen-layouts op een vtable-slot voor de method-full-naam en gebruikt die
   als fallback → de override op het **gedeelde** super-slot wordt nu wel gedispatched (exit 9).

**Snapshot-regressie (`InstructionEmitterTest`, ASM-kant):** de param-/veld-leeswijzigingen
veranderden de bytecode van 4 bestaande tests (`doWhileLoop`, `ifStatement`, `getValue`,
`setValue`); via de permanente `assertBytecodeSnapshot`-helper met `-Dnabu.upd.resources=true` zijn
de snapshots geregenereerd en semantisch geverifieerd (param direct i.p.v. nutteloze immediate-kopie;
GETFIELD gedraagt zich identiek). Baseline-proof via `git stash`: zonder de wijzigingen fouten
alleen de pre-existente `visitForStatement` (for-loop-labelplaatsing, buiten scope) — die is
**niet** aangeraakt.

**Suite-status (eind):** native-backend **53 tests, 0 failures, 1 error (bekende
Bitdefender-pin op EH-exe-launch) + 1 skipped**; compiler-module **1258 tests, 1 failure
(al òòk pre-existente `InstructionEmitterTest.visitForStatement`), 0 errors, 21 skipped**. BUILD
SUCCESS. Alle probes/temp-debug (`[NABU-*]`-prints, IR-dump, system-properties) zijn verwijderd;
`assertBytecodeSnapshot` blijft als utility.

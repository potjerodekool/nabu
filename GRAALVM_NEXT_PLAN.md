# Plan: GraalVM Next (Crema/Ristretto) toepassen op Nabu

Status: discussie-voorstel (2026-11-12). Doel: afstemmen op een richting voordat
er code wordt geschreven. Gebaseerd op JVM Weekly vol. 199 (borrowed engines) en
de bestaande Nabu-architectuur (closed-world AOT via `nabu-native-image` +
bytecode->IR-lowering via `BytecodeReachabilityCompiler`).

## 1. Wat GraalVM Next doet

Klassieke Native Image is een gesloten wereld: alleen bytecode die bij de build
bereikbaar is wordt naar machinecode gecompileerd; klassen die later geladen
worden (plugins, `Class.forName`, `ServiceLoader`, annotation processors,
`javac`) werken niet. GraalVM Next (projectnaam "Bali") doet twee dingen:

- **Crema**: een bytecode-interpreter die *in* de native image zit. De binary
  kan een nieuwe klasse laden, linken met de al gecompileerde klassen en
  interpreteren.
- **Ristretto**: een JIT-compiler boven Crema. Methodes die heet worden gaan
  naar de Graal-compiler en draaien daarna als machinecode.

Resultaat: snelle opstart (meestal gecompileerd) + open wereld (dynamisch
laden werkt). De kosten: een interpreter + een compiler in de runtime, dus
grotere binary en meer geheugen.

## 2. Wat Nabu vandaag is

`nabu-native-image` doet hetzelfde als GraalVM's `native-image`, maar met
een eigen LLVM-backend:

- Closed-world reachability over broncode + reflectie-config
  (`BytecodeReachabilityCompiler`, methode-niveau, niet klasse-niveau).
- Echte JDK-bytecode (Pattern, Matcher, ...) wordt bij de build via
  `BytecodeIrGeneratingVisitor` + `BytecodeMethodLowering` naar IR gelowered en
  daarna naar LLVM/machinecode gecompileerd.
- Objectmodel: `nabu_object` (type-ptr, monitor, thread) + `nabu_type_info`
  (super, interfaces, vtable, itable). GC: Boehm of NONE.
- Runtime is C (`runtime/nabu_runtime.c`, `jcl_lite.c`), geen JVM, geen
  interpreter.

Beperkingen die volgen uit "gesloten wereld":
- Dynamisch laden van onbekende klassen faalt (of eindigt in `--strict`-errors).
- `javac`, annotation processors, agents, scripting, plugins: niet bruikbaar
  in de shipped binary.
- Geen JIT: wat niet bij de build bereikbaar was, is er simpelweg niet.

## 3. Waarom het interessant is voor Nabu

Drie concrete toepassingen, in volgorde van impact:

1. **Tooling als native binary.** `nabu` zelf, `nabu-native-image` en de
   compiler-daemon zouden JDK-tools kunnen draaien die vandaag alleen op een
   echte JVM werken (annotation processors, `javac`-plugins, Lombok-achtige
   processors, test-runners met reflection). Nu moet daarvoor een JVM naast.
2. **Echte "JVM-compat" mode voor `nabu-native-image`.** Een binary die
   `java -jar app.jar`-achtig gedrag heeft: willekeurige bytecode draaien,
   niet alleen de bereikte sluiting. Dat maakt Nabu bruikbaar als
   drop-in runtime, niet alleen als AOT-compiler.
3. **Plugins in Nabu-programma's.** Een programma dat op runtime een plugin-jar
   laadt (SPI/ServiceLoader) zonder dat die bij de build bekend was.

## 4. Vragen (beslispunten)

### V1. Doel en scope

Wat is het primaire doel?

- **A. Tooling-first** (compiler, daemon, native-image-tool kunnen JDK-tools
  draaien). Laagste scope: alleen interpreter, geen JIT nodig.
- **B. Open-world native images** (elke `nabu-native-image`-binary kan
  dynamisch laden). Middelste scope: interpreter + (optioneel) JIT.
- **C. Volledige JVM-compat** (Nabu wordt een JVM die willekeurige bytecode
  draait, zoals Bali). Hoogste scope: interpreter + JIT + JNI + JVMTI + ...
- **D. Onderzoek/experiment** (proof-of-concept, geen productie).

Vraag: welke van A-D? En is Java 21 de doelversie, of wil je naar 25/29
(met Valhalla value objects, wat het objectmodel raakt)?

### V2. Interpreter of compiler-at-runtime?

GraalVM's Crema is een interpreter. Nabu heeft al een bytecode->IR-lowering.
Twee wegen:

- **I1. Echte bytecode-interpreter** in C (of in Java, gecompileerd naar
  native). Interpreteert JVM-bytecode direct. Nieuw werk, maar Crema-model.
- **I2. Runtime lowering naar IR + uitvoeren.** Hergebruik
  `BytecodeIrGeneratingVisitor` op runtime-geladen klassen en voer de IR uit
  via een IR-interpreter, of compileer on-demand. Minder dubbel werk, maar de
  IR is een build-time API die runtime-stabiel moet worden.

Vraag: I1 of I2? En mag de IR-API als "runtime ABI" worden vastgelegd
(stabiel tussen builds)?

### V3. JIT-strategie (als er een JIT komt)

- **J0. Geen JIT.** Alleen interpreter voor dynamische code. Eenvoudigst;
  dynamische code is traag maar correct. Past bij A.
- **J1. Embedded LLVM (ORC JIT).** Hergebruikt de bestaande LLVM-backend als
  JIT. Zwaar: LLVM in de binary (honderden MB), trage eerste compile.
- **J2. Out-of-process.** Runtime stuurt de geladen bytecode naar de
  `nabu-daemon` / een compiler-proces, die een `.so` produceert; runtime
  laadt via `dlopen`. Lichtere runtime, maar daemon-afhankelijkheid en
  latency op eerste call.
- **J3. Tiered hybrid.** Interpreter voor cold code; bij hot methods
  achtergrondcompilatie (J2) en atomisch wisselen van de entrypoint.

Vraag: J0-J3? En is een daemon-afhankelijkheid acceptabel voor een shipped
binary?

### V4. Objectmodel en GC

AOT-klassen en geïnterpreteerde klassen moeten hetzelfde layout delen
(`nabu_object`, `nabu_type_info`, vtable/itable, GC). Voor interpreted
klassen moet bij load een `nabu_type_info` + vtable/itable worden opgebouwd.

- Huidige vtable/itable-logica zit in de backend (compile-time). Voor
  interpreted klassen moet die runtime gereproduceerd worden, of moet de
  backend een "runtime class builder" delen.
- GC: Boehm (niet-deterministisch, maar werkt) vs. NONE (geen collectie).
  Interpreter-allocation moet dezelfde collector gebruiken.
- Monitors/synchronized: runtime ondersteunt het; interpreted code moet
  `nabu_monitorenter/exit` aanroepen.

Vraag: is het objectmodel stabiel genoeg om als runtime ABI te bevriezen?
En welke GC als default voor een interpreter-mode binary?

### V5. `invokedynamic` en lambdas

JDK-bytecode gebruikt `invokedynamic` voor string-concat en lambdas. De
bytecode-lowering heeft daarvoor Fase 3 (bootstrap-trampolines). Een
interpreter moet `invokedynamic` ook afhandelen, of de lowering (I2) moet het
omzetten naar gewone calls.

Vraag: is `invokedynamic`-ondersteuning in scope voor de eerste versie, of
wordt het uitgesteld (met een duidelijke "niet ondersteund"-melding)?

### V6. Native methods / JNI

GraalVM Next via Crema kan JNI niet interpreteren; native methods blijven
een grens. Nabu heeft `jcl_lite.c` met native bindings. Een interpreter die
een klasse laadt die JNI gebruikt, moet die native methode vinden (of
falen).

Vraag: hoe om te gaan met native methods in interpreted klassen? (Falen met
duidelijke fout / binden aan bestaande `jcl_*`-symbols / JNI-ondersteuning
toevoegen.)

### V7. Testing en acceptatie

- Bestaande ITs (`NabuCompilerBackendAcceptanceIT`, e2e) dekken AOT.
- Nieuw: tests die een binary laten draaien die op runtime een klasse laadt
  die niet in de closure zat, en die correct uitvoert.
- Vergelijking met een echte JVM als referentie (zoals Bali's jtreg-aanpak).

Vraag: welke acceptatietest wordt de "graadmeter" (zoals petstore voor
perf-study)? Bijv. `javac` draaien in de binary, of een plugin-systeem.

## 5. Voorstellen (concrete)

### Voorstel A — "Crema-lite" (aanbevolen eerste stap)

Doel: open-world *zonder* JIT. Runtime krijgt een bytecode-interpreter (I1
of I2) die geladen klassen kan uitvoeren, met het bestaande objectmodel en
GC. Geen LLVM in de runtime.

- Fase 1: interpreter-skeleton + class-loader die `.class` leest en een
  `nabu_type_info` opbouwt; uitvoering van een subset van opcodes
  (constants, locals, control flow, calls naar reeds gecompileerde methods).
- Fase 2: objecten/velden/arrays + exceptions.
- Fase 3: `invokedynamic`-trampolines + reflectie.
- Fase 4: JIT-hook (interface) zodat later Ristretto-achtig gedrag kan.

Sterkte: klein, testbaar, lost dynamisch laden op (traag maar correct).
Zwakte: dynamische hot code blijft traag.

### Voorstel B — "JIT via daemon" (J2 + J3)

Bouwt op A: interpreter voor cold code, daemon compileert hot methods naar
`.so`, runtime laadt ze. Hergebruikt de bestaande LLVM-backend en daemon.

Sterkte: echte native snelheid voor hot code, geen LLVM in binary.
Zwakte: daemon-afhankelijkheid, latency, complexiteit van atomisch swappen.

### Voorstel C — "Volledige Bali-achtige JVM" (C + J1)

Nabu wordt een JVM: embedded LLVM JIT, JNI, JVMTI, alles. Maximaal ambitieus;
alleen zinvol als Nabu een zelfstandige runtime wil zijn.

Sterkte: volledige compatibiliteit.
Zwakte: jaren werk, enorme binary, onderhoudslast; concurreert met HotSpot/
GraalVM zelf.

### Voorstel D — "Niet doen, wel documenteren"

Closed-world AOT blijft de kern; documenteer de beperkingen en bied
workarounds (config, `--initialize-at-build-time`, daemon voor tooling).
Kost niets, maar lost dynamisch laden niet op.

## 6. Voorgestelde fases (als A wordt gekozen)

1. **Fase 0 — ABI vastleggen.** `nabu_object`/`nabu_type_info`/vtable-layout
   documenteren als runtime ABI; `nabu_compiled_type_for` uitbreiden zodat een
   interpreted klasse zich kan registreren. (1-2 weken)
2. **Fase 1 — Class loading + interpreter-skeleton.** `.class` parsen,
   `nabu_type_info` opbouwen, methodes interpreteren voor een "hello world"
   die een JDK-klasse dynamisch laadt. (3-6 weken)
3. **Fase 2 — Volledige opcodes + objecten/arrays/exceptions.** Sluit aan bij
   de opcodes die `BytecodeIrGeneratingVisitor` al ondersteunt. (4-8 weken)
4. **Fase 3 — `invokedynamic` + reflectie + native methods.** (4-6 weken)
5. **Fase 4 — JIT-hook + daemon-compilatie (optioneel, B).** (6-10 weken)
6. **Fase 5 — Acceptatietest + jtreg-subset.** (doorlopend)

## 7. Risico's

- **Dubbel werk**: interpreter + AOT-backend onderhouden twee uitvoeringspaden.
- **ABI-drift**: als het objectmodel verandert, breekt de interpreter.
- **Scope-creep**: "volledige JVM" is een valkuil; strict scope (A) houdt
  het beheersbaar.
- **Testlast**: interpreter-correctheid vereist een referentie-JVM als oracle.
- **Prestatie**: interpreter kan 10-100x trager zijn; zonder JIT ongeschikt
  voor hot paths.

## 8. Aanbevolen volgende stap

Kies eerst V1 (doel) en V2 (interpreter vs. runtime-lowering). Op basis daarvan
kan een concreet Fase-0/1-plan met acceptatietest worden geschreven, analoog
aan `BYTECODE_COMPILATION_PLAN.md`. Zonder die keuze is elke implementatie
prematuur.

Open vragen om in de discussie te beantwoorden: V1..V7 hierboven.

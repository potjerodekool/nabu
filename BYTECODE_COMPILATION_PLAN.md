# Plan: JDK-bytecode-gedreven compilatie voor de native backend

## Besluit (2026-10-12)

In plaats van `java.util.regex.Pattern`/`Matcher` als **broncode** te vendoren
(zoals picocli onder `cli-demo/vendor/picocli-src`), compileert de native
backend de **echte, ongewijzigde OpenJDK `.class`-bytecode** van `Pattern`,
`Matcher` en hun afhankelijkheden, geladen via de bestaande
`FileManager`/`StandardLocation`-laag (`CLASS_PATH`/`MODULE_PATH`/
`SYSTEM_MODULES`, bv. tegen `$JAVA_HOME/jmods/java.base.jmod` of de JDK-image
zelf).

Scope: **volledige, generieke JVM-bytecode-ondersteuning** (niet beperkt tot
wat `Pattern`/`Matcher` toevallig gebruiken) — opcodes worden gebouwd per
categorie, niet ad-hoc per crash, al blijft de acceptatietest (5
`greet.GreetCommand.exe`-commando's) de praktische graadmeter.

`invokedynamic` (String-concat via `StringConcatFactory`, lambda's via
`LambdaMetafactory`) krijgt **echte bootstrap-ondersteuning in de
C-runtime** — geen decompilatie naar vervangende instructies.

## Waarom dit kan: bestaande bouwstenen

- `compiler-api/.../tools/FileManager.java` + `StandardLocation`: volledige
  javac-achtige locatie-abstractie, incl. `CLASS_PATH`, `MODULE_PATH`,
  `SYSTEM_MODULES`. Al in gebruik.
- `compiler/.../resolve/asm/ClazzReader.java` (+ `AsmClassBuilder`,
  `AsmMethodBuilder`, `FieldBuilder`, `AsmModuleBuilder`,
  `signature/SignatureParser`): leest vandaag al ECHTE JDK-`.class`-bestanden
  voor **signatures** (klassen, methoden, velden, generics, annotaties) om
  symbolen te resolven — dit is hoe `java.lang.Object`, `String`, etc. nu al
  werken zonder JDK-broncode te compileren. Géén method-bodies.
- `compiler-api/.../backend/ir/*`: de IR die de native backend consumeert
  (`IRModule`, `IRFunction`, `IRBasicBlock`, `IRInstruction`, `IRValue`,
  `IRType`) is **backend-vorm-onafhankelijk** — niet gebonden aan Nabu's
  getypeerde AST. `IrGeneratingVisitor` is slechts ÉÉN manier om die IR te
  vullen (vanuit source-AST). Een bytecode-gedreven lowering die dezelfde
  `IRFunction`/`IRBasicBlock`/`IRInstruction`-API vult is een gelijkwaardige,
  tweede weg naar dezelfde backend.
- `NativeLLVMBackend`/`ReflectionRegistry`/`GraalVMNativeImageConfig`:
  bestaande GraalVM-reachability-metadata-infrastructuur (reflect/resource/
  proxy-config.json) — nuttig patroon, maar dekt geen method-body-
  reachability; die moet nieuw.

## Wat ontbreekt (kern van dit werk)

`NabuCompiler.generateCode()` bouwt vandaag een `IRModule` alléén per
**geparste compilatie-eenheid** (source). Klassen die uitsluitend via
`ClassFinder.fillInClassFromClass()` (bytecode-only, `classSymbol.getClassFile()
!= null`) bekend zijn, krijgen NOOIT een `IrGeneratingVisitor`-pass — hun
method-bodies worden domweg nooit bekeken. Vandaar de huidige
`jcl_nyi(...); abort()`-stub voor `Pattern.compile`.

### Fase 1 — Bytecode→IR lowering (nieuw)
Nieuw package, bv. `io.github.potjerodekool.nabu.compiler.backend.bytecode`:

- `BytecodeIrGeneratingVisitor`: analoog aan `IrGeneratingVisitor`, maar vult
  `IRFunction`/`IRBasicBlock`/`IRInstruction` vanuit ASM's **tree-API**
  (`ClassNode`/`MethodNode`/`InsnList`) in plaats van Nabu's source-AST.
  Dependency: `asm-tree`/`asm-analysis` toevoegen aan `compiler/pom.xml` (nu
  alleen `asm-util`, dat ze transitief meebrengt maar niet declareert —
  expliciet maken voor compile-time zichtbaarheid).
- Operand-stack simulatie per basic block (ASM's `Analyzer`/`BasicInterpreter`
  als startpunt, of eigen stack-map-gebaseerde aanpak — `StackMapTable` is
  verplicht vanaf classfile v50 (Java 6+), dus beschikbaar).
- Basic-block-splitsing op jump-targets + exception-table-ranges
  (try/catch-ranges worden landingpad-basic-blocks; `JSR`/`RET` bestaat niet
  meer sinds class-file v51, dus geen subroutine-opcodes nodig voor JDK17+
  bytecode).
- Opcode-categorieën (volgorde van implementatie, elk een eigen
  patch/testronde tegen `Pattern.class`/`Matcher.class`):
  1. Constants & stack: `(a|i|l|f|d)const*`, `ldc*`, `dup*`, `pop*`, `swap`.
  2. Locals: `(a|i|l|f|d)load/store*`, `iinc`.
  3. Arrays: `newarray`, `anewarray`, `multianewarray`, `*aload`/`*astore`,
     `arraylength`.
  4. Rekenkundig/logisch: `i/l/f/d` add/sub/mul/div/rem/neg, shifts, bitwise,
     `*cmp*`, conversies (`i2l` etc.).
  5. Controlestroom: `if*`, `goto`, `tableswitch`, `lookupswitch`,
     `*return`, `athrow` + exception-table → landingpads.
  6. Objecten/velden: `new`, `checkcast`, `instanceof`, `getfield`/
     `putfield`, `getstatic`/`putstatic`, `monitorenter`/`monitorexit`
     (afbeelden op bestaande lock-runtime indien aanwezig, anders no-op +
     TODO).
  7. Methode-aanroepen: `invokestatic`/`invokevirtual`/`invokespecial`/
     `invokeinterface` → bestaande `CallTargets`/`CallKindResolver`
     hergebruiken voor devirtualisatie/override-resolutie.
  8. `invokedynamic` → Fase 3 (apart, want vereist runtime-bootstrap).
- Type-afleiding: bytecode heeft geen generics meer (erased), dus
  `IRType`-afleiding is eenvoudiger dan vanuit source (verifier-descriptors
  zijn exact) — `TypeInference`-pass kan mogelijk zelfs worden overgeslagen
  voor bytecode-functies.

### Fase 2 — Reachability: welke bytecode-klassen compileren
`generateCode()` itereert nu over `compilationUnits`. Nieuw: na de bestaande
IR-opbouw vanuit source, een **closure-berekening** over alle `IRInstruction`
call-targets (`CallTargets`) en nieuwe/geraakte types: elke aangeroepen
methode/geconstrueerde klasse die alleen een `ClassSymbol.getClassFile()`
heeft (geen source), wordt via `BytecodeIrGeneratingVisitor` alsnog gelowered
tot een `IRModule`/`IRFunction` en toegevoegd aan `allModules` — net als
GraalVM native-image's reachability-analyse, maar hier eenvoudiger: een
work-list/fixpoint-algoritme over de bestaande `IRModule`-call-graph, net zo
lang doorlopend tot geen nieuwe bytecode-klassen meer worden aangetroffen.
Caching: éénmaal gelowerde bytecode-`IRModule`s hergebruiken binnen één
compiler-run (en evt. tussen runs via dezelfde project-local-repo-cache-
conventie als de rest van het project).

### Fase 3 — `invokedynamic`: echte bootstrap-mechanismen
- **`StringConcatFactory.makeConcatWithConstants`**: compileertijd kan de
  bootstrap-methode-aanroep zelf niet native uitvoeren (het is een
  `MethodHandle`-protocol) — de IR-lowering genereert een aanroep naar een
  C-runtime-trampoline (bv. `jcl_invoke_dynamic_string_concat`) die de
  constante recept-string + argumenttypes uit de `invokedynamic`
  bootstrap-args leest en ten uitvoer brengt (dit IS de eigenlijke
  StringConcatFactory-semantiek, alleen in C i.p.v. via `MethodHandle`-
  interpretatie op een echte JVM).
- **`LambdaMetafactory.metafactory`**: elke `invokedynamic`-call-site met deze
  bootstrap genereert een synthetische "lambda-klasse" (vergelijkbaar met wat
  javac's `LambdaToMethodPhase`/`InnerClasses`-mechanisme al doet voor Nabu's
  EIGEN lambda's in `compiler/.../impl/LambdaToMethodPhase.java` — hergebruik
  dat patroon: een class-layout + vtable-entry die naar de `invokedynamic`'s
  statische target-methode (uit de bytecode's eigen `BootstrapMethods`-
  attribuut) wijst, i.p.v. echte `MethodHandle`-objecten te interpreteren.
- Beide hebben een gemeenschappelijke behoefte: de classfile's
  `BootstrapMethods`-attribuut en de `invokedynamic`-instructie's
  `CONSTANT_InvokeDynamic`-constant-pool-entry moeten worden gelezen — ASM's
  tree-API (`InvokeDynamicInsnNode`) geeft dit al direct (naam, descriptor,
  `Handle bsm`, `Object[] bsmArgs`).

## Iteratief ontwikkelpatroon (zelfde als vorige sessie)

1. `mvnd install` vanuit `C:\projects\nabu` na elke wijziging aan
   `compiler`/`compiler-api`/`native-backend`.
2. `mvnd -Pllvm clean package` in `cli-demo`, lees de `.ll`-output als
   ground truth bij onverklaard gedrag (geen gdb-disassembly zonder
   debug-info).
3. `$LASTEXITCODE` in PowerShell, nooit `%ERRORLEVEL%`.
4. Richt de bytecode-opcode-implementatie-volgorde op wat `Pattern.class`/
   `Matcher.class` daadwerkelijk bevat (via `javap -c -p` als referentie om
   te bevestigen welke opcodes/instructies voorkomen), maar houd de
   IMPLEMENTATIE generiek (geen Pattern-specifieke special-casing in de
   bytecode-lowering zelf) — dat is het verschil met de vorige, scoped
   aanpak.

## Open vragen / risico's

- `Character`-Unicode-tabellen in `Pattern`/`UnicodeProp` zijn groot
  (duizenden regels gegenereerde data in bytecode-vorm als static
  initializers/arrays) — static initializers (`<clinit>`) moeten dus ook
  volledig door de bytecode-lowering heen, inclusief grote array-literalen.
- `Unsafe`/`VarHandle`: als deze daadwerkelijk door `Pattern`/`Matcher` (of
  hun transitieve sluiting) worden gebruikt, is runtime-ondersteuning nodig
  — bytecode omzeilt het source-parser-probleem, maar de SEMANTIEK
  (atomics, memory-layout) moet nog steeds ergens landen in de C-runtime.
- Module-system (`java.base`'s `module-info.class`) — waarschijnlijk
  irrelevant voor dit doel (we lezen alleen specifieke klassen, geen
  volledige module-graph-validatie), maar `SYSTEM_MODULES`-locatie moet wel
  de juiste `jrt-fs`/`jmods`-bron kunnen vinden op het doelsysteem (Windows,
  MinGW-toolchain).

## Aanvullende bevinding (zelfde sessie): functienaam-mangling heeft geen descriptor-suffix

`IrGeneratingVisitor` (zowel bij functie-definitie als bij call-emissie) bouwt
de IR-functienaam puur als `eigenaarQualifiedName + "_" + simpleMethodName` —
GEEN parametertype-/descriptor-suffix. Dit is een bestaande, systemische
eigenschap van de hele codebase (niet iets wat door bytecode-lowering wordt
geintroduceerd), maar wel een risico zodra een klasse ECHTE overloads heeft
die allebei via de native backend gecompileerd worden: `Pattern.compile
(String)` en `Pattern.compile(String,int)` zouden allebei naar de LLVM-
functienaam `java.util.regex.Pattern_compile` wijzen — een naamsbotsing.

De nieuwe `BytecodeIrGeneratingVisitor` gebruikt VOORLOPIG dezelfde
naamconventie (dus GEEN eigen suffix), om consistent te blijven met hoe
bestaande call-sites (vanuit source, via `IrGeneratingVisitor`) de
aanroep-naam opbouwen — anders linkt een source-aanroep naar `Pattern.compile`
niet met de bytecode-gelowerde functie. Als een build daadwerkelijk een
naamsbotsing raakt (twee overloads met dezelfde eigenaar+simpleName), is de
juiste fix een GECOORDINEERDE wijziging aan BEIDE kanten (definitie- en
call-site-naamopbouw, dus zowel in `IrGeneratingVisitor` als in de nieuwe
bytecode-visitor) om een descriptor-gebaseerde suffix toe te voegen — niet een
eenzijdige aanpassing aan slechts één kant. Dit is een aparte, apart te
plannen taak zodra de eerste botsing zich voordoet.

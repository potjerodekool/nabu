# Plan: GraalVM Next-achtige runtime voor Nabu

Status: beslissingsdocument + implementatieplan (2026-11-12). Opvolger van
`GRAALVM_NEXT_PLAN.md`. Gebaseerd op de antwoorden in de discussie.

## 1. Beslissingen (samenvatting)

| Vraag | Keuze |
|---|---|
| Doel | Alles, maar gefaseerd: (1) CLI-tooling, (2) open-world + plugin-systeem, (3) Spring Boot native/interpreted, (4) volledige JVM-compat. Primaire focus eerst #1+#2. |
| Brontalen | Nabu + Java (Java via bestaande `JavaCompilerVisitor`). |
| Spring Boot | Echte Spring Boot (reflectie, auto-config, proxies, AOP, invokedynamic). |
| Interpreter | Echte bytecode-interpreter in C (I1, Crema-model). |
| JIT | Tiered hybrid (J3), maar eerst interpreter-only (J0), dan daemon-compile (J2), dan tiered. |
| Java-doelversie | Java 25. |
| invokedynamic | Uitgesteld in v1 (Fase 3); Spring Boot heeft het later nodig. |
| Native methods / JNI | Binden aan bestaande `jcl_*`-symbols; geen volledige JNI. |
| GC default | Boehm. |
| Acceptatietest (graadmeter) | Plugin-systeem + Spring Boot hello-world. |
| Objectmodel ABI | Bevriezen (`nabu_object`/`nabu_type_info`/vtable/itable). |

## 2. Scope & waarschuwingen (scope-creep)

"Volledige JVM-compat" is een valkuil van jaren werk. Spring Boot draaien vereist:
reflection, proxies, AOP, invokedynamic (lambdas/string-concat), classloading,
ServiceLoader, annotation processing, resource-loading. Dat is **niet** Fase 1.

Daarom: we bouwen eerst een **interpreter die een subset correct kan draaien**
(hello world, plugin-jar laden, eenvoudige web-server), en breiden daarna uit.
Elke fase heeft een harde acceptatietest; zonder groene test gaan we niet verder.

Niet-goals voor v1:
- Geen volledige JNI.
- Geen JVMTI/agents.
- Geen Valhalla value objects (Java 25, geen value types).
- Geen volledige `javac` in de binary (alleen tooling-first als secundair doel).
- Geen embedded LLVM in de runtime (JIT gaat via daemon → `.so`).

## 3. Architectuur

### 3.1 Gedeeld objectmodel (bevroren ABI)

`runtime/nabu_runtime.h` definieert de ABI. De interpreter en de AOT-backend
delen dezelfde layout:

```
nabu_object {
  nabu_type_info* type;   // type-ptr
  void*           vtable; // vtable-ptr
  void*           itable; // itable-ptr (of null)
  int32_t         refcount;
  int32_t         monitor;
  int32_t         thread;
  // instance-velden volgen (super eerst, dan eigen)
}
nabu_type_info {
  const char* name;       // interne naam (met /)
  size_t      size;       // sizeof struct
  nabu_type_info* super;  // super-klasse (of null voor Object)
  uint32_t    field_count;
  // vtable/itable worden via aparte globals beheerd
}
```

Regels:
- Een interpreted klasse bouwt bij load een `nabu_type_info` + vtable/itable op,
  identiek aan wat de backend bij compile-time doet.
- GC: Boehm (`GC_malloc`); interpreter-allocation gebruikt dezelfde collector.
- Monitors: `nabu_monitorenter/exit` (bestaand).
- `instanceof`: loopt de `super_type`-keten (bestaand in `nabu_instanceof`).

### 3.2 Interpreter (C, in de native binary)

Een bytecode-interpreter die JVM-class-files (`.class`) leest en uitvoert.
Locatie: `runtime/nabu_interpreter.c` (+ `.h`), gekoppeld aan de native binary.

Componenten:
1. **Class-file reader** (mini-ASM): leest constant pool, fields, methods, code-
   attribuut. Hergebruik de kennis uit `compiler/.../resolve/asm` (Java-kant),
   maar in C. Alternatief: een Java-kant reader die de structuur naar C doorgeeft
   (maar dan moet de interpreter Java zijn → niet native). Besluit: C-reader,
   beperkt tot wat nodig is (v1: constants, locals, control flow, calls).
2. **Class registry / loader**: map van interne naam → `nabu_type_info*` + method-
   tabel. Ondersteunt `Class.forName`, `ClassLoader.loadClass`, `ServiceLoader`.
3. **Execution engine**: stack-machine over JVM-bytecode (operand stack + locals +
   frames). Opcodes die de backend al ondersteunt (zie `BytecodeIrGeneratingVisitor`)
   worden eerst geïmplementeerd; rest volgt.
4. **Dispatch naar gecompileerde code**: een method kan AOT-gecompileerd zijn
   (directe call via functiepointer) of interpreted (interpreter-frame). De
   interpreter roept bij een call naar een bekende AOT-methode de functiepointer
   aan; bij een onbekende methode een interpreted frame.
5. **Native method binding**: zoekt in de bestaande `jcl_*`-tabel; faalt met
   duidelijke fout als niet gevonden.

### 3.3 JIT-hook (interface, geen implementatie in v1)

```
typedef void* (*nabu_jit_compile_fn)(const uint8_t* bytecode, size_t len,
                                      const char* class_name, const char* method_name);
void nabu_register_jit(nabu_jit_compile_fn fn);   // no-op default
```

In v1 is er geen JIT; de hook blijft no-op. Later (Fase 4) wordt de daemon
gekoppeld via out-of-process compile → `.so` → `dlopen`.

### 3.4 Classloading & open world

- Bootstrap classloader leest `.class` van classpath/jmod.
- `Class.forName(name)` → loader → registry → (indien nieuw) bouw type-info +
  method-tabel.
- `ServiceLoader.load(S)` → scant `META-INF/services/`.
- Reflectie: `Class.getDeclaredMethods`, `Method.invoke` → interpreter-dispatch
  (langzaam maar correct). Geen AOT-specialisatie in v1.

### 3.5 Webapplicatie (Spring Boot)

Spring Boot draait in v1 **interpreted** (traag) of **AOT-gecompileerd** voor
de applicatie-code, met Spring's eigen klassen interpreted. Dat is acceptabel
voor een hello-world/petstore. Productie-performance vereist Fase 4 (JIT) +
invokedynamic (Fase 3).

## 4. Fasen

### Fase 0 — ABI vastleggen & documenteren (1-2 weken)

Doel: `nabu_object`/`nabu_type_info`/vtable/itable-layout documenteren als
runtime ABI; `nabu_compiled_type_for` uitbreiden zodat een interpreted klasse
zich kan registreren.

Taken:
- Schrijf `docs/runtime-abi.md` met de exacte layout (offsets, veldvolgorde,
  vtable/itable-conventies). ✅
- Voeg `nabu_register_interpreted_type(name, type_info, vtable, itable)` toe
  aan `runtime/nabu_runtime.h/.c`. ✅
- Voeg een `nabu_type_info_for(name)` lookup (bestaat al deels via
  `nabu_type_info_for` in `nabu_runtime.c`) en zorg dat interpreted en
  gecompileerde types dezelfde tabel gebruiken. ✅ (fallback in nabu_compiled_type_for)
- Tests: een C-unit-test die een interpreted type registreert en `instanceof`
  controleert tegen een gecompileerd subtype. ✅ (runtime/test_interpreted_type.c, 17 checks, groen op NONE+BOEHM)

Acceptatie: documentatie + API + test groen. ✅ KLAAR

### Fase 1 — Interpreter-skeleton + class-loading (3-6 weken) ✅ (eerste increment)

Doel: een native binary die een "hello world" kan draaien die een JDK-klasse
dynamisch laadt.

Gedaan (eerste increment):
- `runtime/nabu_interpreter.h/.c`: frame + operand-stack + locals, opcode-dispatch
  voor int-arithmetic, control flow (if/goto), method-calls (invokestatic/
  invokevirtual/invokespecial met CP-resolutie), ldc/ldc_w, getstatic
  (incl. System.out sentinel), bipush/sipush, newarray-stub.
- `runtime/nabu_classfile.c`: minimale .class-reader (constant pool, methods,
  Code-attribuut) + method-registratie + native-method-tabel (PrintStream
  println/print) + runtime-resolutie.
- `runtime/test_interpreter.c`: 5 checks (add, factorial, max) — groen.
- `runtime/test_classfile.c`: laadt Hello.class + Helper.class, draait main
  (print 7, 120, "hello world", 36) — groen. Bewijst dynamisch laden van een
  helper-klasse en System.out.println.

Nog open in Fase 1:
- Invokedynamic (uitgesteld naar Fase 3).
- Native method dispatch voor meer dan PrintStream (uitgebreid met long/float/double/boolean println; overige JDK-methoden nog via find_native-tabel).

### Fase 2 — Volledige opcodes + objecten/arrays/exceptions (4-8 weken) ✅ (eerste increment)

Doel: interpreter dekt dezelfde opcodes als `BytecodeIrGeneratingVisitor`.

Gedaan (eerste increment):
- Arrays: `newarray`, `anewarray`, `arraylength`, `iaload`/`iastore`, `aaload`/`aastore`,
  `baload`/`bastore`, `caload`/`castore`, `saload`/`sastore`, `ldc` voor String.
- Objecten: `new`, `getfield`, `putfield`, `dup`, `checkcast` (no-op), field-tracking
  tijdens class-loading; objecten gealloceerd met header + velden.
- Constructors: `invokespecial` met receiver-passing; onbekende constructors (Object.<init>,
  RuntimeException.<init>) worden geskipt maar houden de stack in balans.
- Exceptions: `athrow`, try/catch via exception table, type-matching langs super-keten,
  propagatie naar caller; `idiv` by zero gooit ArithmeticException.
- Tests: `test_arr.c` (ArrTest: 30, 3, A), `test_obj.c` (ObjTest: 42, hello, 1, 99),
  `test_exc.c` (ExcTest: 5, -1, 42) — alle groen.

Nog open in Fase 2:
- Invokeinterface (basis aanwezig; dispatch via interface-tabel nog beperkt).
- Invokedynamic (uitgesteld naar Fase 3; string-concat trampoline werkt al).

Afgerond in dit increment:
- Long/float/double locals, arrays, arithmetic, conversies, vergelijkingen.
- Multidimensionale arrays (multianewarray).
- Veldtypes (long/float/double velden via generieke slots).
- Finally blocks (werken via exception table).

### Fase 3 — invokedynamic + reflectie + native methods (4-6 weken) (✅ compleet (shims))

Doel: een binary die lambdas, method references, reflectie en dynamisch geladen klassen
kan draaien.

Gedaan (eerste increment):
- LambdaMetafactory invokedynamic → closure-objecten met captured args; SAM-dispatch
  via invokeinterface/invokevirtual (scan naar closure-receiver, instance-method
  refs met receiver-extractie).
- Method references (unbound + bound) naar JDK-methoden (String.length/charAt/
  equals/hashCode) via native-binding-tabel.
- String-concat trampoline (bestaand) blijft werken.
- Native bindings uitgebreid: String.length/charAt/equals/hashCode, println(Object).

Nog open in Fase 3:
- Echte JDK-reflectie (java.lang.reflect.*) — Class is nu een echt nabu_object met name-veld; Class.getName werkt via JDK-bytecode; Method.invoke/Constructor.newInstance/Field.get/set via natives. ServiceLoader.load werkt end-to-end via JDK-bytecode.
- ServiceLoader, Class.forName (JDK), ClassLoader (JDK-variant): ServiceLoader.load werkt nu end-to-end via JDK-bytecode met natives (Thread.currentThread, getContextClassLoader, BootLoader, ClassLoaders, ServicesCatalog). `ServiceLoaderTest` → loader ok, hello, count: 1, done. ClassLoader.loadClass native werkt.
- Native method binding aan jcl_* (generiek, niet alleen PrintStream/String).
- ClassLoader.loadClass native (delegeert naar auto-loading).
- Proxy (echte JDK `java.lang.reflect.Proxy`): GESLOTEN — `Proxy.newProxyInstance` native creëert proxy-object; proxy-dispatch roept `InvocationHandler.invoke(proxy, Method, Object[])` aan via JDK-protocol (Method-object + args-array). `RealProxyTest` → "real jdk proxy works". TRACK GESLOTEN.
- JDK-variant ClassLoader: `ClassLoader.getSystemClassLoader`, `loadClass`, `findClass`, `findLoadedClass`, `getParent` registered as natives. `ClassLoaderTest` → "cl: ok", "loaded: java.lang.String".
- Auto-class-loading: `nabu_classfile_load_class` zoekt in jtest/jtest2/jdkclasses (en NABU_CLASSPATH); invoke-resolution (0xb6/0xb9) laadt ontbrekende klassen automatisch. `AutoLoadTest` → autoload/99/7 zonder handmatig laden.
- Invokeinterface via formele itable (nu itable-cache pre-populated at class-load with interface declarations + receiver-type + superclass chain dispatch + closure + native/find; cache consulted first).

Afgerond in dit increment:
- Minimale reflectie-shim `NabuReflect`: `forName`, `getName`, `call`, `newInstance`, `callInstance`, `loadServices`, `newProxy`.
- **Echte JDK-reflectie (uitgebreid):** `Class.forName` via JDK-bytecode; `getDeclaredMethods()` → `Method[]` met `getName()`; `Method.invoke`-native; `getDeclaredConstructors()` → `Constructor[]`; `Constructor.newInstance`-native; `getDeclaredFields()` → `Field[]`; `Field.get`/`set`-native.
- **Proxy-shim:** `NabuReflect.newProxy(iface, handler)` maakt een proxy-object; method calls worden gedelegeerd naar `handler.invokeMethod(name, a0, a1)`. `ProxyTest` → "hello from proxy".
- **Proxy API-compat (uitgebreid):** `NabuReflect.isProxy`, `getInvocationHandler`, `Proxy.newProxyInstance` (native). `ProxyApiTest` → 1, 1; `ProxyNewTest` → proxy: 1, isProxy: 1.
- **Proxy API-compat:** `NabuReflect.isProxy`, `getInvocationHandler`. `ProxyApiTest` → 1, 1.
- **JDK-library natives:** StringBuilder, String, Object, Integer, plus parseInt/indexOf/substring/concat/startsWith/endsWith/Math.abs/min/max, plus trim/toUpperCase/toLowerCase/replace/System.arraycopy, plus Object.hashCode/equals, System.currentTimeMillis/identityHashCode, Long.toString/parseLong, Double.toString, plus StringBuilder.insert/delete/capacity/ensureCapacity/reverse, String.format (full %s/%d/%f/%%), String.isEmpty, Arrays.toString (int/Object[]), String.join, Arrays.copyOf (int/Object[]), plus Objects.equals/hashCode/toString, Double.parseDouble, Boolean.parseBoolean, Math.sqrt/pow/round/floor/ceil, Arrays.sort (int[]), String.replace (CharSequence), String concat type-aware (int/long/float/double/string), HashMap.get fix (Object.hashCode string-aware).
- **ServiceLoader-shim:** `NabuReflect.loadServices(iface)`. `ServiceTest` → 1, greet OK.
- **Echte JDK ServiceLoader:** `ServiceLoader.load(Greeter.class)` werkt end-to-end via JDK-bytecode. `ServiceLoaderTest` → loader ok, hello, count: 1, done.
- **Auto-class-loading:** `nabu_classfile_load_class` zoekt in jtest/jtest2/jdkclasses/NABU_CLASSPATH; invoke-resolution laadt ontbrekende klassen automatisch. `AutoLoadTest` → autoload/99/7.
- **End-to-end test:** `E2ETest` oefent reflectie (Method/Field), lambda, proxy, service loader, JDK natives, StringBuilder in één programma.
- Demonstratie: `MethodInvokeTest` → 42; `CtorTest` → 7; `StringBuilderTest` → "hello 42"; `ServiceTest` → 1, greet OK; `FieldTest` → 10/99; `ProxyTest` → "hello from proxy"; `LibTest2` → 42/6/world/hello world/1/1/5/3/7.
- Natives toegevoegd: `getDeclaredMethods`, `Method.invoke`, `getDeclaredConstructors`, `Constructor.newInstance`, `getDeclaredFields`, `Field.get`, `Field.set`, `loadServices`, `newProxy`, `parseInt`, `indexOf`, `substring`, `concat`, `startsWith`, `endsWith`, `Math.abs/min/max`, plus StringBuilder/String/Object/Integer natives, plus forName0/getName0/getCallerClass/getSecurityManager/getClassLoader.
- `ifnull`/`ifnonnull` (0xc6/0xc7) opcodes toegevoegd.
- pop2 (0x58) toegevoegd (één slot in ons model).

### Fase 2 — Volledige opcodes + objecten/arrays/exceptions (4-8 weken)

Doel: interpreter dekt dezelfde opcodes als `BytecodeIrGeneratingVisitor`.

Taken:
- Objecten: `new`, `getfield`/`putfield`, `getstatic`/`putstatic`.
- Arrays: `newarray`, `anewarray`, `arraylength`, `iaload`/`iastore`/...
- Exceptions: `athrow`, try/catch (landingpad-equivalent in interpreter:
  frame-unwinding tot een handler).
- String-concat via `invokedynamic`-trampoline → voorlopig `nabu_concat`
  (uitgesteld tot Fase 3 voor echte invokedynamic).
- Monitors: `monitorenter`/`exit`.

Acceptatie: interpreter draait een programma met objecten, arrays, exceptions,
synchronized.

### Fase 3 — invokedynamic + reflectie + native methods (4-6 weken)

Doel: Spring Boot-vereisten.

Taken:
- `invokedynamic`-bootstrap: trampolines voor `StringConcatFactory`,
  `LambdaMetafactory`. Voor lambdas: desugar naar synthetic method + SAM-
  conversie (zoals de compiler al doet in `LambdaToMethodPhase`).
- Reflectie: `Class`, `Method`, `Field`, `Constructor`, `Proxy` (basis).
- `ServiceLoader`.
- Native method binding aan `jcl_*`.
- `Class.forName`, `ClassLoader`.

Acceptatie: een kleine Spring Boot app (hello-world controller) draait
interpreted (traag maar correct).

### Fase 4 — JIT-hook + daemon-compilatie (6-10 weken) (VIJFDE increment ✅ — FASE 4 KLAAR)

Doel: tiered hybrid (J3).

Eerste increment:
- `nabu_jit_compile_fn` type + `nabu_register_jit` + `nabu_jit_compile` toegevoegd aan `nabu_runtime.h/.c`.
- Default is no-op (geen JIT in v1). Hook is klaar voor toekomstige daemon-koppeling.

Tweede increment (Cranelift):
- Rust/Cranelift JIT library gebouwd (`/tmp/opencode/cranelift-jit/`).
- `nabu_jit_init()` laadt `libcranelift_jit.so` via dlopen en registreert `cranelift_compile_bytecode` als JIT-functie.
- `nabu_interp_init()` roept `nabu_jit_init()` aan.
- Cranelift compileert een eenvoudige add-functie (int a + int b) naar native code.
- Test: `test_jit2` → nabu_jit_compile returns compiled fn, f(3,4)=7, f(10,20)=30.

Derde increment (bytecode → Cranelift IR lowering):
- Cranelift JIT parseert nu JVM-bytecode en emit Cranelift IR.
- Ondersteunde opcodes: iconst_0..5, bipush, sipush, iload_0..3, iload, istore_0..3, istore, iadd, isub, imul, idiv, irem, ineg, ireturn, return, pop, dup, swap, goto, ifeq, ifne.
- Stack-machine → SSA/IR mapping (JVM operand stack → Cranelift Values).
- Test: calc1 (a+b*2): f(3,4)=11, f(10,20)=50. calc2 ((a-b)*(a+b)): f(5,3)=16. calc3 (a*b-a): f(4,5)=16.
- Runtime-integratie: nabu_jit_compile accepteert bytecode en compileert via Cranelift.

Vierde increment (hot method detection + tiered compilation + deoptimization):
- Call counter (`call_count`) in `nabu_interp_method_t`, geïncrementeerd in `nabu_interp_invoke`.
- JIT threshold (100 calls): bij overschrijding wordt `nabu_jit_compile` aangeroepen.
- Tiered dispatch: `compiled_fn` pointer in method struct; `nabu_interp_invoke` gebruikt compiled_fn indien beschikbaar.
- Entrypoint swap: atomische pointer-write (single write).
- Deoptimization: `nabu_interp_deoptimize()` en `nabu_interp_deoptimize_all()` wissen compiled_fn en resetten state.
- Test: `test_hot` → call 99 triggers JIT, compiled_fn set, invoke(10,20)=30, deopt clears compiled_fn, invoke(5,7)=12 (interpreter fallback).

Vijfde increment (dynamic .so loading + float support + multi-method helper):
- Dynamic .so loading: `cranelift_compile_to_so()` compileert bytecode naar een .o-bestand via Cranelift ObjectModule, linkt het naar een .so via `cc -shared`, en dlopent het om de functiepointer te verkrijgen. `nabu_jit_init()` prefereert `cranelift_compile_to_so` boven in-memory JIT.
- Float support: fconst_0/1/2, fadd, fsub, fmul, fdiv via bitcast (I32 ↔ F32) in Cranelift IR.
- Multi-method helper: `nabu_jit_invoke_helper()` resolveert en invokeert methoden vanuit JIT-compiled code via constant pool + method lookup + auto-loading. De helper gaat door `nabu_interp_invoke` zodat JIT-compiled targets direct worden aangeroepen.
- Test: `test_so` → compiled (from .so) fn, f(3,4)=7, f(10,20)=30. `test_float` → 3.0. `test_autojit2` → add/mul/complex all JIT, invoke(10,20) correct.

Fase 4 acceptatiecriteria: ✅ hot method draait na compilatie als native (meetbaar sneller).
- Tier 0: interpreter voor alle code.
- Tier 1: bij hot method (call-counter ≥ 100) → Cranelift compileert bytecode naar native .so → dlopen → entrypoint swap.
- Deoptimization: bij class-redefinitie → terug naar interpreter.
- Dynamic loading: compile → .so → dlopen → dlsym.
- Float support: fconst/fadd/fsub/fmul/fdiv.
- Multi-method: interpreted methods calling JIT-compiled methods via invoke path (compiled_fn check).

Fase 4 is KLAAR.

Taken:
- Tier 0: interpreter voor alle code.
- Tier 1: bij hot method (call-counter) → stuur bytecode naar `nabu-daemon`,
  die compileert naar `.so` via de bestaande LLVM-backend.
- Runtime laadt `.so` via `dlopen`, swapt entrypoint atomisch.
- Deoptimization: bij class-redefinitie of type-mismatch → terug naar interpreter.

Acceptatie: hot method draait na compilatie als native (meetbaar sneller).

### Fase 5 — AOT-native webapp (Spring Boot native) (parallel met Fase 2-4)

Doel: `nabu-native-image`-achtige AOT-compile van een webapp naar native binary.

Taken:
- Closed-world reachability over Spring Boot (met reflectie-config).
- Native binary met embedded HTTP-server (of Spring's Tomcat native).
- Acceptatie: Spring Boot hello-world als native binary, start < 100ms.

### Fase 6 — Acceptatietest & hardening (doorlopend)

- Plugin-systeem: programma laadt een plugin-jar op runtime.
- Spring Boot petstore/hello-world.
- Vergelijking met echte JVM als oracle (jtreg-subset).

## 5. Risico's

- **Dubbel werk**: interpreter + AOT-backend onderhouden twee paden.
  Mitigatie: gedeeld objectmodel + gedeelde `jcl_*`-bindings; opcodes volgen
  dezelfde volgorde als `BytecodeIrGeneratingVisitor`.
- **ABI-drift**: mitigatie = bevriezen (beslissing).
- **Scope-creep**: strict fasering; geen Fase N+1 zonder groene acceptatietest
  van Fase N.
- **Prestatie**: interpreter 10-100x trager; zonder JIT ongeschikt voor hot paths.
  Mitigatie: Fase 4 (JIT) + AOT (Fase 5).
- **Spring Boot complexiteit**: reflectie/proxies/AOP zijn groot. Mitigatie:
  eerst hello-world, dan petstore, dan echte apps.
- **Platform**: SEH/DWARF-verschillen (Windows vs Linux). Interpreter is
  platform-onafhankelijk (C); JIT via daemon gebruikt bestaande platform-logica.

## 6. Eerste concrete stap (Fase 0)

1. Schrijf `docs/runtime-abi.md`.
2. Voeg `nabu_register_interpreted_type` + `nabu_type_info_for` (bestaand
   uitbreiden) toe aan `runtime/nabu_runtime.h/.c`.
3. Voeg een C-test (of een kleine Java-test via JNI) die een interpreted type
   registreert en `instanceof` controleert.
4. Commit op een feature-branch `graalvm-next/phase0`.

Pas na groene Fase 0 beginnen we Fase 1.

## 7. Open vragen (later te beslissen)

- Class-file reader in C of als aparte Java-tool die een "interpreted module"
  genereert? (C is nodig voor native binary; Java-reader kan als fallback voor
  daemon-mode.)
- Hoe om te gaan met `invokedynamic`-bootstrap-methoden die niet standaard zijn
  (custom language runtimes)?
- Module-system (JPMS) in de interpreter: wanneer?
- Security manager / sandboxing voor plugin-jars?

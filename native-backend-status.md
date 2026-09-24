# native-backend status

Stand: alle 63 tests in native-backend groen (0 failures, 0 errors; 2 skipped).

## Wat werkt

- Frontend → IR → LLVM-emissie voor het hele batch-verhaal (overloaded klassen,
  inheritance, super, interfaces).
- Type-gerichte dispatch: `tryEmitVirtualCall` / `tryEmitInterfaceCall` met
  receiver-conventie (slot 0 = `this`), resolver via overload-keys
  (`FnKeys.fnKey(naam, fn.params)`); de vtable-slot-index per klassenoverload.
- Exception-handling: landingpads + `nabu_can_catch`-typefiltering in IR, en de
  SEH-personality + LSDA bij het produceren van een native object.
- Reflectie-metadata-emissie naar Java-klassen + config-consumer
  (ReflectionRegistry/loadNativeImageConfigs).
- Linken via MinGW-gcc (gnu-triple) met `runtime/nabu_runtime.c`.
- Object-emissie: LLVM-backend maakt `.o` + `.exe` aan (zie echter de
  beperking hieronder). `e2e.ll`/`e2e_full.ll` worden naar
  `%LOCALAPPDATA%\Temp\opencode\` gekopieerd ter inspectie.

## Beperkingen op deze machine (AV)

`NativeExceptionEndToEndTest.fullSourceToExeCatchesException` is @Disabled: de
volledige pipeline t/m het linken slaagt (object 1728 B, exe 88336 B), maar
Windows/een AV-scanner houdt dit specifieke LLVM-gelinkte exe vast. Symptomen:

- `CreateProcess error=5, Toegang geweigerd` bij het starten.
- Lezen faalt met "bestand wordt gebruikt door een ander proces" (zelfs 8 s na
  linken); een kopie verdween van schijf (quarantaine/verwijdering).
- Handmatig gcc-gemaakte exe's in dezelfde map draaien wél (ook met dezelfde
  `nabu_runtime.c`-link) — het verschil is de LLVM-COFF-objectfile.
- Op een machine zonder die AV interceptie moet de test kunnen worden
  opgeheven (verwijder @Disabled) en de run verifiëren.

## Status t.o.v. cli-demo-native (plan.md)

- Werkt: IR-emissie, inheritance/e2e, exception-handling, reflectie-metadata +
  config-consumer, calls met receiver/overloads.
- Ontbreekt voor cli-demo-native: Fase E4 (lite-reflect), Fase 3 (jcl-lite),
  Fase 4 (volledige picocli-LLVM-batch).
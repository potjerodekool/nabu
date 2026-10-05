# native-backend status

Stand: `mvn -pl native-backend verify` **101 tests groen, 0 failures, 0 errors,
0 skipped** (97 surefire + 4 failsafe), mits een MinGW-gcc beschikbaar is:

    mvn -o -pl native-backend verify -Dnabu.gcc=C:\programs\mingw64\mingw64\bin\gcc.exe ^
                                        -Dnabu.toolchain.bin=C:\programs\mingw64\mingw64\bin

Zonder gcc-configuratie slaan de 12 native e2e-tests zichzelf over (assumption in
`NativeToolchain.assumeGcc()`, met een expliciete melding); gcc staat op deze
machine niet op de PATH, dus de properties/env-vars zijn hier nodig.

## Wat werkt

- Frontend → IR → LLVM-emissie voor het hele batch-verhaal (overloaded klassen,
  inheritance, super, interfaces).
- Type-gerichte dispatch: `tryEmitVirtualCall` / `tryEmitInterfaceCall` met
  receiver-conventie (slot 0 = `this`), resolver via overload-keys
  (`FnKeys.fnKey(naam, fn.params)`); de vtable-slot-index per klassenoverload.
- Exception-handling: landingpads + `nabu_can_catch`-typefiltering in IR, en de
  SEH-personality + LSDA bij het produceren van een native object. Een
  niet-overeenkomende catch-clause wordt opnieuw gegooid i.p.v. `_Unwind_Resume`
  te gebruiken (zie hieronder).
- Reflectie-metadata-emissie naar Java-klassen + config-consumer
  (ReflectionRegistry/loadNativeImageConfigs).
- Linken via MinGW-gcc (gnu-triple) met `runtime/nabu_runtime.c`.
- De `*IT`-tests draaien nu in de build: `maven-failsafe-plugin` staat in
  `native-backend/pom.xml` (`NabuCompilerBackendAcceptanceIT`,
  `ReflectionBridgeE2eIT`, `SourceInheritanceE2eIT`). Ze draaiden daarvoor nooit,
  omdat surefire alleen `*Test` pakt.
- Object-emissie: LLVM-backend maakt `.o` + `.exe` aan. De volledige
  bron→exe→run-c inclusief try/catch slaagt; de artefacten staan in
  `target/e2e/exception-e2e.{o,ll,exe}` ter inspectie.

## Openstaande punten / bekende randgevallen

- **Oneindige lus bij een niet-overeenkomende catch-clause (opgelost).** Op
  Windows x64 SEH springt `_Unwind_Resume` terug in het al geïnstalleerde
  landingspad; de personality wordt niet opnieuw geraadpleegd, dus
  `nabu_catch` → `nabu_resume` → landingspad herhaalt zich. `nabu_resume`
  (`runtime/nabu_runtime.c`) doet op `_WIN32` daarom een verse
  `nabu_throw`/`_Unwind_RaiseException`; zonder handler volgt `abort()` met
  `nabu: onbehandelde exception`. Dit was de oorzaak van de 30-minuten-hang van
  de volledige suite.
- **AV-blokkade op een bestaande bestandsnaam (geen codeprobleem).** De
  volledige pipeline slaagt, maar op deze machine staat de naam `e2e*.exe` in
  `native-backend/target/e2e/` in de quarantainelijst van de AV: `ld.exe` krijgt
  `cannot open output file ...: Permission denied`, ook voor het schrijven van
  een lege file met die naam (`e2e3.txt`/`a.exe` in dezelfde map lopen wel).
  Vandaar de artefactnaam `exception-e2e.exe` in de test. Oude vermeldingen
  ("CreateProcess error=5", "het verschil is de LLVM-COFF-objectfile") golden op
  de eerdere lus, niet op de objectfile.
- `Linker.wellKnownWindowsToolDirs()` bevat bewust geen machine-specifieke
  paden; een eigen MinGW hoort via `nabu.gcc`/`NABU_GCC` +
  `nabu.toolchain.bin`/`NABU_TOOLCHAIN_BIN` (ook nodig zodat
  `libgcc_s_seh-1.dll` bij het draaien wordt gevonden).
- Geen CI-job voor native-backend: `.github/workflows/compiler-tests.yml` bouwt
  alleen `compiler` + `compiler-test`.
- `nabu_type_info_for` zet `super_type = NULL` voor handgebouwde objecten, dus
  `nabu_can_catch` matcht alleen op de exacte interne naam (geen
  subklassen-hiërarchie). De runtime-e2e-tests gebruiken daarom
  gegooid-type = clause-type.

## Status t.o.v. cli-demo-native (plan.md)

- Werkt: IR-emissie, inheritance/e2e, exception-handling, reflectie-metadata +
  config-consumer, calls met receiver/overloads.
- Ontbreekt voor cli-demo-native: Fase E4 (lite-reflect), Fase 3 (jcl-lite),
  Fase 4 (volledige picocli-LLVM-batch).
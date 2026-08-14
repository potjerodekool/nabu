# Native LLVM Backend — Implementatieplan

## Overzicht

De native backend vertaalt Nabu IR naar LLVM IR via Bytedeco LLVM bindings,
compileert naar een object file en linkt naar een uitvoerbaar bestand.

## Archictuur

```
NativeLLVMBackend (Backend)
  └─ LLVMModuleEmitter
       ├─ GlobalEmitter       — IRGlobal → LLVM globals (strings, variabelen)
       ├─ FunctionEmitter     — IRFunction → LLVM functie-declaraties + bodies
       │    └─ InstructionEmitter — IRInstruction → LLVMBuild* aanroepen
       ├─ TypeMapper          — IRType → LLVMTypeRef
       └─ ConstantResolver    — IRValue const → LLVMValueRef
  └─ Linker                  — object files → executable (gcc/clang/link.exe)
```

## Bestanden

| Bestand | Beschrijving |
|---|---|
| `NativeLLVMBackend.java` | Hoofdingang. Implementeert `Backend`. Pipeline: emit → validate → optimize → object → link |
| `LLVMModuleEmitter.java` | Orkestratie: globals → signaturen → bodies |
| `FunctionEmitter.java` | Functie-signaturen + body-emissie |
| `InstructionEmitter.java` | IR instructies → LLVM builder calls |
| `TypeMapper.java` | IRType → LLVMTypeRef conversie |
| `ConstantResolver.java` | IRValue constanten → LLVMValueRef (inclusief string-deduplicatie) |
| `GlobalEmitter.java` | IRGlobal → LLVM globals |
| `LoopBuilder.java` | While/For/DoWhile loop-patronen (staat in `backend` package, niet `native_llvm`) |
| `Linker.java` | Platform-specifiek linken (gcc/clang/link.exe) |

---

## Instructie-ondersteuning

### IRInstruction types

| Instructie | Native | ASM | Opmerking |
|---|---|---|---|
| `BinaryOp` | ✅ | ✅ | |
| `Alloca` | ✅ | ✅ | |
| `AllocaArray` | ❌ | ✅ | **Nog te implementeren** — LLVM `array alloca` |
| `Load` | ✅ | ✅ | |
| `Store` | ✅ | ✅ | |
| `Call` | ✅ | ✅ | |
| `IndirectCall` | ✅ | ✅ | |
| `Branch` | ✅ | ✅ | |
| `CondBranch` | ✅ | ✅ | |
| `Return` | ✅ | ✅ | |
| `Cast` | ✅ | ✅ | Ondersteunt Int→Int, Int→Float, Float→Int, bitcast |
| `InstanceOf` | ❌ | ✅ | **Nog te implementeren** — LLVM `icmp` + RTTI |
| `Throw` | ❌ | ✅ | **Nog te implementeren** — LLVM `resume` of `call abort` |
| `Pop` | ❌ | ✅ | **Nog te implementeren** — overslaan of waarderesultaat negeren |
| `Phi` | ✅ | ❌ | ASM eist eliminatie vóór emissie |
| `Move` | ✅ | ✅ | |

### BinaryOp operanden

| Op | Native | ASM | Opmerking |
|---|---|---|---|
| `ADD` | ✅ | ✅ | |
| `SUB` | ✅ | ✅ | |
| `MUL` | ✅ | ✅ | |
| `DIV` | ✅ | ✅ | `LLVMBuildSDiv` (signed) |
| `MOD` | ✅ | ✅ | `LLVMBuildSRem` (signed) |
| `AND` | ✅ | ✅ | |
| `OR` | ✅ | ✅ | |
| `XOR` | ✅ | ✅ | |
| `BITAND` | ❌ | ✅ | **Valt naar default→exception** — moet gemapt worden naar `LLVMBuildAnd` |
| `BITOR` | ❌ | ✅ | **Valt naar default→exception** — moet gemapt worden naar `LLVMBuildOr` |
| `BITXOR` | ❌ | ✅ | **Valt naar default→exception** — moet gemapt worden naar `LLVMBuildXor` |
| `EQ` | ✅ | ✅ | |
| `NEQ` | ✅ | ✅ | |
| `LT` | ✅ | ✅ | |
| `LTE` | ✅ | ✅ | |
| `GT` | ✅ | ✅ | |
| `GTE` | ✅ | ✅ | |

---

## Type-ondersteuning

### IRType

| Type | Native | ASM | Opmerking |
|---|---|---|---|
| `Int(bits)` | ✅ | ✅ | |
| `Float(bits)` | ✅ | ✅ | 32 → float, 64 → double |
| `Bool` | ✅ | ✅ | LLVM i1 |
| `Ptr(pointee)` | ✅ | ✅ | Opaque pointers (geen pointee in LLVM IR) |
| `Array(elem, size)` | ✅ | ✅ | |
| `Void` | ✅ | ✅ | |
| `Function(ret, params)` | ✅ | ✅ | |
| `Struct(fields)` | ✅ | ❌ | JVM kent geen structs |

### IRValue

| Value | Native | ASM | Opmerking |
|---|---|---|---|
| `Temp(name, type)` | ✅ | ✅ | |
| `Named(name, type, ownerType, isStatic)` | ✅ | ✅ | |
| `ConstInt(value, type)` | ✅ | ✅ | |
| `ConstFloat(value, type)` | ✅ | ✅ | |
| `ConstBool(value)` | ✅ | ✅ | |
| `ConstString(value)` | ✅ | ✅ | |
| `ConstNull(type)` | ✅ | ✅ | |
| `ConstUndef(type)` | ✅ | ✅ | |
| `ConstClass(type)` | ❌ | ✅ | **Nog te implementeren** — LLVM `@typeof` of type-metadata |
| `Values(values)` | ❌ | ✅ | **Nog te implementeren** — meerdere waarden sequentieel emitteren |
| `FunctionRef(name, fnType)` | ✅ | ✅ | |

---

## Nog te implementeren

### Prio 1 — Essentieel

1. **`BITAND`, `BITOR`, `BITXOR` in BinaryOp**
   - File: `InstructionEmitter.java:91-107`
   - Oplossing: Voeg drie cases toe aan de switch, map naar `LLVMBuildAnd`/`LLVMBuildOr`/`LLVMBuildXor`
   - LLVM's `and`/`or`/`xor` zijn bitewijs — identiek aan wat we nodig hebben

2. **`AllocaArray` instructie**
   - File: `InstructionEmitter.java` (nieuwe methode)
   - LLVM: `LLVMBuildArrayAlloca(builder, allocType, size, name)`
   - Oplossing: Voeg `emitAllocaArray()` toe aan `InstructionEmitter.emit()` switch

### Prio 2 — Belangrijk

3. **`InstanceOf` instructie**
   - LLVM heeft geen directe `instanceof` — vereist runtime type info (RTTI)
   - Optie 1: Genereer een `call` naar een runtime helper (`__instanceof(ptr, classId)`)
   - Optie 2: Gebruik LLVM's `icmp` met type-metadata (complexer)
   - Aanbevolen: Optie 1 — declareer een extern `@nabu_instanceof(i8*, i8*) -> i1` functie

4. **`Throw` instructie**
   - LLVM heeft geen `throw` — uitzonderingen worden afgehandeld via `resume` na `invoke`
   - Optie 1: Implementeer als `call @nabu_throw(ptr)` gevolgd door `unreachable`
   - Optie 2: Gebruik `setjmp`/`longjmp` patroon (platform-afhankelijk)
   - Aanbevolen: Optie 1 met runtime helper

5. **`Pop` instructie**
   - Simpel: voeg `emitPop()` toe die het resultaat van de vorige instructie resolveert maar niet opslaat
   - Of: overslaan als de vorige instructie geen side-effect heeft

### Prio 3 — Nice-to-have

6. **`ConstClass` waarde**
   - LLVM heeft geen `Class` type — vereist runtime class metadata
   - Implementeer als een globale string met de klassenaam, of als een pointer naar een class-descriptor struct
   - Vereist een runtime class registry

7. **`Values` waarde**
   - Meerdere waarden sequentieel emitteren
   - Implementeer: itereren over de lijst en elke waarde via `resolveValue()` resolveren

8. **Float BinaryOps**
   - Huidige implementatie gebruikt `LLVMBuildSDiv`/`LLVMBuildSRem` — correct voor integers maar niet voor floats
   - Floats moeten `LLVMBuildFDiv`/`LLVMBuildFRem` gebruiken
   - Vereist type-check op de operands

---

## Optioneel / Toekomst

9. **Debuginfo (DWARF)**
   - LLVM ondersteunt DIBuilder voor debug-info
   - Koppel `SourceLocation` aan LLVM instructions via `LLVMSetCurrentDebugLocation`

10. **Windows link.exe ondersteuning**
    - `Linker.java` heeft commentaar over `link.exe` maar gebruikt nu `clang`
    - Optioneel: uncomment en test MSVC-linker pad

11. **Platform-specifieke optimalisaties**
    - `LLVMCreateTargetMachine` gebruikt nu `LLVMCodeGenLevelDefault`
    - `CompileOptions.OptLevel` wordt al doorgegeven maar kan verder geoptimaliseerd worden

12. **Externe functie-detectie voor libc**
    - `printf`, `malloc`, `free` etc. moeten als extern gedeclareerd worden
    - Huidige aanpak: de frontend markeert ze als `isExternal()`, de backend geeft ze `ExternalLinkage`

13. **LLVM Pass Pipeline verfijning**
    - Huidig: `mem2reg` / `default<O2>` / `default<O3>`
    - Mogelijkheid om Nabu-specifieke passes toe te voegen

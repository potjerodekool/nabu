---
layout: page
title: Testing
---

The Nabu compiler has a layered testing strategy, from unit tests to end-to-end compilation and execution tests.

## Test modules

- **`compiler`** — ~129 unit/integration test classes covering parser, enter/resolve, method resolution, access checks, the ASM emitter (with golden bytecode listings), IR optimization passes, widening/boxing, the annotation-processing environment, Lombok handlers, and Kotlin language-support fixtures.
- **`compiler-test`** — end-to-end tests that compile `.nabu` sources under `src/main/nabu` and **execute the produced bytecode** with assertions.
- **`testing`** — a scaffold (currently "Not started") for utilities to test compiler plugins.

## End-to-end examples

The `compiler-test` module compiles and runs real programs:

- **Generics + interfaces + annotations** (`Box.nabu`, `Category.nabu`)
- **Sealed hierarchy + `instanceof` pattern** (`Shape.nabu` → `ShapeKindTest`)
- **Loops including enhanced-for over collections** (`Loops.nabu`)
- **Classic, enum, and boxed switches** (`SwitchIt.nabu` → `SwitchItTest`)
- **Lambdas / method references** in predicate DSLs (`*Predicates.nabu`)
- **Records** (`IdAndName.nabu`)
- **Hibernate/JPA plugin integration** with `persistence.properties` and `hibernate.cfg.xml`

## Bytecode verification

The ASM emitter is tested against golden bytecode listings (`InstructionEmitterTest`, `ASMByteCodeEmitterTest/record.txt`), and `CheckClassAdapter` validates emitted class files.

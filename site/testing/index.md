---
layout: page
title: Testing
---

The Nabu compiler has a layered testing strategy, from unit tests to end-to-end compilation and execution tests.

## Test modules

- **`compiler`** — about 1,350 tests in some 100 test classes, covering the parser, enter/resolve, method resolution, access checks, the ASM emitter (with golden bytecode listings), IR optimization passes, widening/boxing, the annotation-processing environment, Lombok handlers, and Kotlin language-support fixtures. The `*RunTest` classes compile a small program with the ASM backend, load it and run it (for example `LambdaTargetTypeRunTest`, `NestmateAccessRunTest`, `GenericCallRunTest`). `PicocliCliDemoJvmRunTest` compiles the whole [picocli example](/examples/picocli-demo/) and checks its five commands; it is skipped when the demo project is not present.
- **`native-backend`** — about 130 tests of the LLVM backend: unit tests of the emitted IR and object model, and `*E2eIT` tests that compile a Java program natively, link and run it, and compare its output. The `*E2eIT` tests need a MinGW `gcc` and are skipped without one; they take several minutes each because they compile the JDK closure. See [LLVM internals](/backends/llvm/internals/#tests).
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

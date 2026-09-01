---
layout: page
title: Annotation Processing
---

The Nabu compiler implements the **JSR-269** annotation processing API (`javax.annotation.processing`), giving it surprisingly complete support for running standard annotation processors — including Lombok-style handlers and real-world processors like Hibernate's `hibernate-jpamodelgen` and MapStruct.

## Location

The implementation lives in `compiler/src/main/java/io/github/potjerodekool/nabu/compiler/annotation/processing/`.

## Key classes

- **`JavacProcessingEnvironment`** — implements `javax.annotation.processing.ProcessingEnvironment`; bundles `Messager`, `JavacFiler`, `JavacElements`, and `JavacTypes`. `getSourceVersion()` returns `RELEASE_17`. Its `round(rootElements, processorStates)` drives `processor.process(annotations, roundEnvironment)`.
- **`JavacFiler`** — implements `Filer`; tracks generated source/class files (via file-object close callbacks), resolves modules/packages from `/`-separated names, and clears sets each round.
- **`JavacElements`** / **`JavacTypes`** — wrap Nabu element/type utilities and bridge to `javax.lang.model` via `ElementWrapperFactory` / `TypeWrapperFactory`.
- **`JavacRoundEnvironment`** — implements `RoundEnvironment`; `getElementsAnnotatedWith` scans root elements' annotation mirrors.
- **`JavacMessager`** — forwards diagnostics to the compiler's `DiagnosticListener`.
- **`ProcessorState`** — pairs a `Processor` with the `TypeElement`s of its supported annotation types.

Wrapper packages provide `JTypeElement`, `JPackageElement`, `JVariableElement`, `JExecutableElement`, `JTypeParameterElement`, `JModuleElement`, and the type wrappers (`JDeclaredType`, `JPrimitiveType`, `JExecutableType`, `JArrayType`, `JTypeVariable`, `JWildcardType`, etc.).

## How processors run

The driver (`NabuCompiler.runAnnotationProcessors`) follows this flow:

1. **`resolveClasses`** wraps class symbols as `TypeElement`s.
2. **`findAnnotationProcessors`** uses `ServiceLoader.load(Processor.class, classLoader)` (classloader from `ANNOTATION_PROCESSOR_PATH`, else `CLASS_PATH`).
3. **`createProcessingEnvironment`** wires up the Javac utilities.
4. `processor.init(env)` is called, creating one `ProcessorState` per processor.
5. Rounds are repeated: `round()` runs processors → the filer's generated source files are read → the generated files are parsed and entered → `prepareForRound()` clears the sets — until nothing new is generated.

## Lombok-style handlers

Built-in handlers implement `Getter`, `Setter`, `Data`, and `Constructors` automation, demonstrating how the compiler can integrate with code-generating processors.

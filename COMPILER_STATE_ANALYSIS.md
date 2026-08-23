# Nabu Compiler — State Analysis

**Date:** 2026-08-23
**Baseline:** branch `main`, commit `35ae190`
**Reference language level:** Java 20 (JLS SE 20; standard features through Java 20, preview features noted as such)

This document analyses the current state of the Nabu compiler repository: what is implemented, what is partial, and what is missing when measured against the Java 20 language feature set. It ends with a prioritised roadmap.

---

## 1. Executive Summary

The Nabu compiler is a polyglot source-to-JVM-bytecode compiler. Its strongest layers are the **frontend** (an almost complete JLS-derived grammar for the Nabu language plus a Java 20 grammar for interop) and the **symbol/type model** (class-file reading via ASM, generics, method resolution). The **backend** compiles a substantial working subset of the language to class files (verified end-to-end in `compiler-test`), but several Java-20-era constructs are parsed yet **not code-generated**: pattern matching in `switch`, `finally` blocks, try-with-resources cleanup, anonymous class bodies, and string switches. Sealed classes are parsed and modeled but the `PermittedSubclasses` attribute is not emitted.

Two experimental backends exist: an LLVM-based native backend (`native-backend` + C runtime) and a stub for the JDK classfile API (`backend/java`). The repo is currently mid-refactor of the IR/ASM pipeline (uncommitted changes, branches `asm-classic`, `flip-condition`, `jump-return`).

| Layer | Maturity |
|---|---|
| Nabu parser (grammar + AST builder) | ★★★★★ Near complete |
| Java 20 parser (interop path) | ★★★★☆ Complete for parsing/symbols |
| Symbol & type resolution | ★★★★☆ Broad; some type-mapping gaps |
| Check phase (type/flow diagnostics) | ★★★☆☆ Basic |
| Lowering / desugaring | ★★★☆☆ Records/enums/lambdas done; try-with-resources, finally, anon classes missing |
| IR + optimizations | ★★★☆☆ SSA/GVN/DCE present; switch lowered naively |
| JVM bytecode emission | ★★★★☆ Working subset; class file v17 default |
| Tooling (Maven plugin, daemon, JPA plugin) | ★★★☆☆ Functional prototypes |
| Native (LLVM) backend | ★★☆☆☆ Experimental |
| CI / test automation | ★★☆☆☆ Rich tests, no build/test workflow on CI |

---

## 2. Repository Inventory

| Module | Purpose | State |
|---|---|---|
| `compiler` | Core compiler: phases, resolve, backends (~129 test classes) | Active development |
| `compiler-api` | JPMS API wrapper | Contains only `module-info.java` |
| `nabu-lang` | Nabu ANTLR grammars + `NabuCompilerVisitor` (3,297 lines) | Complete for grammar coverage |
| `native-backend` | LLVM-based native code emitter | Experimental WIP |
| `runtime` | C runtime (`nabu_runtime.c/h`, CMakeLists) for native backend | Minimal |
| `maven-plugin` | `CompileMojo` / `TestCompileMojo` | Working prototype |
| `jpa-plugin` | DSL plugin transforming JPA criteria joins/casts | Working prototype |
| `jpa-support` | Runtime support types (`Join`, `JpaPredicate`, …) | Small, functional |
| `nabu-daemon` / `nabu-client` | Lightweight compile-daemon protocol (socket-based) | Prototype with tests |
| `tools` | Project-model tooling (`project.toml`/`module.toml`) | Prototype |
| `testing` | Empty scaffold | Not started |

### Compiler pipeline (per docs/compiler-design and `compiler/impl`)

```
Parse → Enter → Resolve → Transform → Check → LambdaToMethod → Lower → IR (+opt) → ASM bytecode
```

Key classes: `CompilerContextImpl`, `EnterPhase`, `ResolverPhase`, `TransformPhase`, `CheckPhase`, `LambdaToMethodPhase`, `Lower`, `IrGeneratingVisitor`, `AsmByteCodeEmitter`.

Class files are emitted at **version 61 (Java 17)** by default (`JavaVersion.MINIMAL_VERSION`), up to `V23`.

---

## 3. Java 20 Feature Checklist

Legend: ✅ implemented · 🟡 partial (parsed/modeled but incomplete) · ❌ not implemented

### 3.1 Types & declarations

| Feature | Status | Evidence / Notes |
|---|---|---|
| Classes, inheritance, abstract/final | ✅ | `NabuParser.g4` §8.1; full pipeline tested |
| Interfaces incl. `extends` lists | ✅ | Grammar §9.1 |
| Default methods (Java 8) | ✅ | `interfaceFunctionModifier: ... 'default'` |
| Static interface methods (8) | ✅ | Same rule set |
| Private interface methods (9) | ✅ | `interfaceFunctionModifier: 'private'`; emission verified by resolve tests |
| Enums (5) | ✅ | `EnumCodeGenerator` synthesises `$VALUES`, `$values()`, `values()`, `valueOf()`; enum-constant bodies supported |
| Records (16) | ✅ | `recordDeclaration` grammar incl. varargs components; `RecordCodeGenerator` expands compact constructors and component accessors; `ACC_RECORD` emitted (`AsmByteCodeEmitter.java:87`) |
| Compact record constructors | ✅ | `CompactConstructorDeclaration` → `RecordCodeGenerator.processCompactConstructor` |
| Sealed classes / interfaces (17) | 🟡 | Grammar supports `sealed`/`non-sealed`/`permits`; `ClassSymbol.getPermittedSubclasses()` exists; **no `PermittedSubclasses` class-file attribute emitted, no exhaustiveness/sealing enforcement found** |
| Annotations & annotation interfaces | ✅ | Normal/marker/single-element, arrays, nested annotations, `default` values; retention/target emission via `AsmByteCodeEmitter.emitAnnotations` |
| Generics: parameterised types | ✅ | Full signature support both directions (ASM `SignatureParser` visitors) |
| Bounded type parameters (`<T extends A & B>`) | ✅ | `typeBound`, `additionalBound` |
| Wildcards `? extends / ? super` | ✅ | `CWildcardType`, visitor support |
| Generic method invocation inference | 🟡 | Inference machinery exists (`UndetVarType`, `TypeMapFiller/Applier`) but completeness vs. javac untested beyond simple cases |
| `var` (10) | ✅ | `localVariableType: ... 'var'`; used throughout test sources |
| Text blocks (15) | ✅ | Lexer token `TextBlock` handled in `visitLiteral` |
| Modules (JPMS, 9) | 🟡 | Full module grammar + `ModuleSymbol` model + ASM reading; source-module compilation path present (`ModuleSourcePathLocationHandler`) but end-to-end tests focus on reading `java.base` jmod stubs |
| Nested member classes | 🟡 | `NestingKind.MEMBER` modeled and `$`-flatnames built; **no `InnerClasses` attribute emission found in ASM backend** |
| Local classes (16+ grammar) | 🟡 | Parsed (`localClassOrInterfaceDeclaration`); no dedicated lowering observed |
| Anonymous classes | ❌ | Parsed (`classBody?` in instance creation); body generation is commented out in `IrGeneratingVisitor.visitNewClass` (line ~932) — instances are created without the body class |
| Top-level functions (Nabu-specific) | ✅ | `ordinaryCompilationUnit: ... functionDeclaration*` |

### 3.2 Expressions

| Feature | Status | Evidence / Notes |
|---|---|---|
| Full operator set (arith, bit, shift `<< >> >>>`, logical, ternary, compound assignment) | ✅ | Grammar §§15.14–15.26; opcodes resolved per primitive type in `FunctionEmitter.emitBinOp` |
| String concatenation | ✅ | Lowered to `invokedynamic` `StringConcatFactory.makeConcatWithConstants` (`FunctionEmitter.java:240`) — modern javac-equivalent |
| Array creation/access, multi-dim | ✅ | `NewArrayExpression`, `ArrayAccessExpressionTree`; `emitNewArray`/`emitArrayLoad` |
| Array initialisers | ✅ | `arrayInitializer` grammar + visitor |
| Lambda expressions (8) | ✅ | Desugared to synthetic private methods (`LambdaToMethodPhase`), called through SAM conversion (`FunctionEmitter.emitSamConversion`, invokedynamic/LambdaMetafactory style) |
| Method references incl. constructor refs (8) | ✅ | `functionReference` grammar incl. `Type::new`; `MemberReference` IR path |
| `instanceof` type patterns (16) | ✅ | `relationalExpression oper='instanceof' referenceType \| pattern`; bound-variable binding handled |
| Switch **expressions** (14) + arrow rules + multi-label cases + `yield` | 🟡 | Grammar and AST complete; IR generated as a **chain of comparisons** (no `tableswitch`/`lookupswitch`); works for int/boxed/char/byte/short/enums in tests |
| Pattern matching in `switch` (preview in 20) | ❌ | AST nodes exist (`PatternCaseLabel`, `TypePattern`) and the *Java* visitor builds them (`JavaCompilerVisitor.java:1908`), but `IrGeneratingVisitor.visitPatternCaseLabel` returns null — **no codegen**, no null handling, no exhaustiveness |
| Record patterns (preview in 20) | ❌ | Not in grammar; `pattern` rule only permits `typePattern` |
| Class literals (`Foo.class`) | ✅ | `classLiteral` + expected-bytecode test (`classLiteral-expected.txt`) |
| `this`/`TypeName.this`, qualified `super` | ✅ | Grammar §§15.8–15.12 |
| Conditional expressions, casts (incl. intersection-cast syntax) | ✅ | `CastExpressionTree`, `IntersectionTypeTree` |

### 3.3 Statements & control flow

| Feature | Status | Evidence / Notes |
|---|---|---|
| `if/else`, `while`, `do-while`, basic `for` | ✅ | Tested end-to-end (`Loops.nabu`, `InstructionEmitterTest/*Loop.txt`) |
| Enhanced for (arrays + iterables) | ✅ | `for (... in ...)`; iterator/array lowering in `Lower` phase |
| Labeled statements + labeled `break`/`continue` | ✅ | `resolveLabelTarget`, break-target stack in switch IR |
| `switch` statement (classic + arrow) | ✅/🟡 | Works via comparison-chain lowering; enum switches remapped through a synthetic `$SwitchMap`-style member class (`Lower.visitEnumSwitchStatement`) |
| **String switch** (7) | ❌ | No hashCode/equals lowering found anywhere |
| Exceptions: throw / catch / multi-catch | ✅ | `catchType: unannClassType ('\|' classType)*`; `TryCatchRange` metadata → exception table |
| **`finally`** | ❌ | `getFinalizer()` read only to compute a flag that changes nothing (`IrGeneratingVisitor.visitTryStatement`:1587–1593 identical branches); finally bodies are never emitted |
| Try-with-resources (7/9) | ❌ | Resources are parsed and stored (`TryStatementTree.getResources`) but never desugared — no `close()` calls, no suppression |
| `synchronized` methods/blocks | ✅ | `monitorenter`/`monitorexit` emitted (`visitSynchronizedStatement`) |
| `assert` | ✅ | `visitAssertStatement` (IR line 1439) |
| Instance/static initialiser blocks | 🟡 | Parsed; field-init codegen documented in compiler-design doc; static-init ordering edge cases untested |

### 3.4 Interop & platform integration

| Area | Status | Notes |
|---|---|---|
| Reading Java 17–23 class files (generics, modules) | ✅ | `resolve/asm` package incl. mutable types |
| Parsing Java 20 sources | ✅ | ANTLR `Java20Parser.g4` + `JavaCompilerVisitor` (2,303 lines, zero unsupported constructs) — used to mix Java+Nabu sources |
| Compiling Java sources to bytecode | ❌ (by design) | Delegated to `javac` in the Maven flow; `backend/java/JavaBackend` is a commented-out experiment using the JDK classfile API |
| Annotation processing (`javax.annotation.processing`) | 🟡 | `JavacProcessingEnvironment/Filer/Messager/Elements/Types`, round handling, Lombok-style handlers (Getter/Setter/Data/Constructors); `ToNabuMapper` still throws for ERROR/UNION/INTERSECTION/unknown types (`ToNabuMapper.java:73–108`) |
| Compiler plugins / DSL SPI | ✅ | `extension.PluginRegistry` (service-loaded `plugin.xml`), `CodeTransformer`; JPA plugin demonstrates join-cast DSL + operator overloading |

---

## 4. Test Coverage

**Unit/integration (`compiler/src/test`, 129 files):** parser, enter/resolve, method resolution, access checks, ASM emitter with golden bytecode listings (`InstructionEmitterTest`, `AsmMethodByteCodeGenerator`, `ASMByteCodeEmitterTest/record.txt`), IR optimisation passes, widening/boxing, annotation-processing environment, Lombok handlers, Kotlin language-support fixtures.

**End-to-end (`compiler-test`):** compiles `.nabu` sources under `src/main/nabu` and executes the produced bytecode with assertions:
- generics + interfaces + annotations (`Box.nabu`, `Category.nabu`)
- sealed hierarchy + `instanceof` pattern (`Shape.nabu` → `ShapeKindTest`)
- loops incl. enhanced-for over collections (`Loops.nabu` → `LoopsTest`)
- classic + enum + boxed switches (`SwitchIt.nabu.bck` → `SwitchItTest`)
- lambdas/method refs in predicate DSLs (`*Predicates.nabu`, demo repositories)
- records (`IdAndName.nabu` → `IdAndNameTest`)
- Hibernate/JPA plugin integration (`persistence.properties`, `hibernate.cfg.xml`)

**Untested features (no example or assertion found):** switch pattern matching, record patterns, string switch, `finally`, try-with-resources, anonymous classes, local classes, module *compilation*, sealed enforcement, float/double arithmetic paths.

**CI:** `.github/workflows` contains only `deploy-docs.yml`. **No build/test pipeline runs on push/PR** — the largest single process gap.

---

## 5. Key Gaps and Risks

1. **Parsed-but-not-generated constructs** silently produce wrong programs (e.g., a `finally` body disappears; an anonymous class body vanishes). A diagnostic (“not yet supported”) should be emitted until each is implemented.
2. **Sealed classes are half-wired:** without the `PermittedSubclasses` attribute and sealing checks, compiled sealed hierarchies are not actually sealed at runtime.
3. **Switch codegen is O(cases) comparison chains** — correct but far from `tableswitch`/`lookupswitch`; also blocks string-switch entirely.
4. **Boxing coverage is asymmetric:** boxers exist for byte/int/long/short (`resolve/impl/box`); char/boolean/float/double rely on other paths and lack dedicated tests.
5. **Annotation-processing interop** fails hard (UnsupportedOperationException) on union/intersection/error types coming from javac-side processors.
6. **InnerClasses/Nest attributes** are not emitted, which will matter for access bridges once local/anonymous classes land.
7. **No CI gate** means regressions in the mid-refactor IR/ASM area (visible as uncommitted working-tree changes and stray `*.bck`, `~`, `build.bat`, `actual.txt` artifacts) can land unnoticed.
8. **Native LLVM backend and C runtime** are exploratory; they duplicate emission logic and need an explicit keep/park decision.
9. **Generic-method inference** machinery exists but has no conformance suite comparable to javac’s inference tests.

---

## 6. Roadmap (prioritised)

### P0 — Correctness guardrails
1. Add CI: build + run all module tests on push/PR (mirror `compiler-test` harness).
2. Emit **hard errors/warnings** for every parsed-but-unsupported construct (`finally`, try-with-resources resources, anonymous bodies, switch patterns, string switch).
3. Clean the working tree: commit or discard WIP files (`*.bck`, `~`, scratch outputs).

### P1 — Finish the Java 17 core
4. Implement `finally` emission (inline-duplication or JSR-free subroutine style consistent with modern javac).
5. Desugar try-with-resources (expanded variable + `close()` + suppressed-exception handling).
6. Emit `PermittedSubclasses`; add compile-time sealed-subclass/exhaustiveness validation.
7. Emit `InnerClasses` (and later NestHost/NestMembers); implement anonymous-class lowering to synthetic classes.
8. String-switch lowering (hashCode dispatch + equals chain, javac-compatible).
9. Complete boxing: add char/boolean/float/double boxers + tests.
10. Replace comparison-chain switches with `tableswitch`/`lookupswitch` selection heuristics (density/sparsity).

### P2 — Java 20 parity
11. Type patterns in `switch` labels: resolution, binding scoping, null/default handling, guarded patterns (`case X x when cond` is 21-preview — decide scope), then codegen via instanceof-chains.
12. Record patterns (decomposition + recursive patterns) behind a flag, mirroring their preview status in Java 20.
13. Switch-expression exhaustiveness analysis (needed anyway once patterns land).
14. Strengthen generic-method inference with a targeted conformance test suite.

### P3 — Platform & ecosystem
15. Annotation-processing interop: implement union/intersection mapping in `ToNabuMapper`.
16. Module-mode compilation E2E test (compile a small multi-module project against real `java.base` jmod).
17. Decide the fate of `JavaBackend` (classfile-API experiment) and the native LLVM backend; either invest (dedicated plan/tests) or archive.
18. Grow the daemon/client into the incremental-build story (wire into Maven plugin optionally).

---

## 7. Appendix — Quick reference of evidence locations

| Topic | File |
|---|---|
| Nabu grammar | `nabu-lang/src/main/antlr4/nabu/NabuParser.g4` |
| Java 20 grammar | `compiler/src/main/antlr4/java/Java20Parser.g4` |
| Nabu AST builder | `nabu-lang/.../lang/support/nabu/NabuCompilerVisitor.java` |
| Java AST builder | `compiler/.../lang/support/java/JavaCompilerVisitor.java` |
| Class-file version policy | `compiler/.../tools/JavaVersion.java` (`MINIMAL_VERSION = V17`) |
| Bytecode emitter | `compiler/.../backend/asm/AsmByteCodeEmitter.java`, `FunctionEmitter.java` |
| String concat indy | `FunctionEmitter.java:240` |
| Enum synthetics | `compiler/.../backend/lower/codegen/EnumCodeGenerator.java` |
| Record expansion | `compiler/.../backend/lower/codegen/RecordCodeGenerator.java` |
| Enum switch remap | `compiler/.../backend/lower/Lower.java:430` |
| Switch IR (chain compare) | `compiler/.../backend/ir/IrGeneratingVisitor.java:~1450` |
| Pattern label no-op | `IrGeneratingVisitor.java:1818` |
| Finally no-op | `IrGeneratingVisitor.java:1572–1593` |
| Anonymous body skipped | `IrGeneratingVisitor.java:931–935` |
| Lambdas desugar | `compiler/.../frontend/desugar/lambda/LambdaToMethod.java`, `impl/LambdaToMethodPhase.java` |
| Sealed API surface | `ast/symbol/impl/ClassSymbol.java:333` |
| AP type-mapping gaps | `annotation/processing/java/element/ToNabuMapper.java:73–108` |
| End-to-end examples | `compiler-test/src/main/nabu/io/github/potjerodekool/nabu/example/*.nabu` |

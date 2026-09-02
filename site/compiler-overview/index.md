---
layout: page
title: Compiler Overview
---

The Nabu compiler is a **source-to-JVM-bytecode** compiler. This page gives a high-level overview of what the compiler is and how source code flows through it to become executable bytecode.

## High-level pipeline

Source files are processed through a well-defined sequence of phases:

<div class="pipeline">
  <span class="step">Parse</span><span class="arrow">→</span>
  <span class="step">Enter</span><span class="arrow">→</span>
  <span class="step">Resolve</span><span class="arrow">→</span>
  <span class="step">Transform</span><span class="arrow">→</span>
  <span class="step">Check</span><span class="arrow">→</span>
  <span class="step">Lambda</span><span class="arrow">→</span>
  <span class="step">Lower</span><span class="arrow">→</span>
  <span class="step">IR (+opt)</span><span class="arrow">→</span>
  <span class="step">Bytecode</span>
</div>

The **frontend phases** (Parse through Lower) produce a lowered abstract syntax tree. The **backend phases** then generate an intermediate representation (IR), optimize it, and emit bytecode through a pluggable backend.

## Supported languages

The compiler can parse and mix multiple input languages in a single project:

| Language | Status | Details |
|---|---|---|
| **Nabu** | Native, full support | The compiler's own language, based on Java + Kotlin syntax |
| **Java** (up to 20) | Interop, full parse | Java sources are parsed and modeled for interop; bytecode generation is delegated to `javac` in the Maven flow |
| **Kotlin** | Experimental | Grammar and language support exist; only top-level class declarations are handled |

## Supported backends

The same frontend and IR can be targeted to multiple backends:

| Backend | Status | Output |
|---|---|---|
| **ASM** | Default, production | JVM bytecode (class file v17+ via ASM) |
| **Java** | Experimental | JVM bytecode (via JDK 24 ClassFile API) |
| **LLVM** | Experimental | Native code (via LLVM + C runtime) |

## Extensibility

The compiler is extended through a plugin system. `PluginRegistry` discovers plugins from the classpath and from a user directory, reading each plugin's `plugin.xml` manifest. Extension points include:

- **`language-parser` / `language-support`** — add a new input language
- **`backend`** — add a new output backend
- **`element-resolver`** — add DSL element resolution
- **`code-transformer`** — transform a DSL AST into a normal AST

## Entry point

The top-level driver is the `NabuCompiler` class, which implements the `tools.Compiler` interface. Its `compile(CompilerOptions)` method orchestrates all phases. The compiler is driven programmatically, through the [Maven plugin](/maven-plugin/), or through the compile daemon.

## Next steps

- Understand each phase in detail in [Compiler Design](/compiler-design/).
- Read about how to [add a language](/plugins/adding-language/) or [extend the compiler](/plugins/).

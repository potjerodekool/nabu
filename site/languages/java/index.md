---
layout: page
title: Java Interop
---

The Nabu compiler provides **Java interoperability out of the box**. Java 20 is supported, and Nabu and Java sources can be mixed in a single project.

## What works

- **Nabu can call Java** code and **Java can call Nabu** code.
- Java and Nabu sources can be mixed in one project.
- The compiler reads Java class files (Java 17–23) including generics and modules via its `resolve/asm` package.
- Java **sources** (up to Java 20) are parsed and modeled using the ANTLR `Java20Parser.g4` grammar and the `JavaCompilerVisitor`.

## How Java sources are compiled

Nabu parses Java sources for interop and symbol modeling, but does **not** generate bytecode for Java itself. In the Maven flow, Java-to-bytecode is delegated to `javac`. The `nabu-maven-plugin` must be placed **before** the `maven-compiler-plugin` so that Nabu sources are compiled first and available to Java.

See [Maven Plugin](/maven-plugin/) for the full configuration.

## Reference language level

The reference language level for the frontend is **Java 20** (JLS SE 20). Interop and mixed input languages support both Nabu and Java 20 features.

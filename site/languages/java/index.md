---
layout: page
title: Java Interop
---

The Nabu compiler provides **Java interoperability out of the box**. Java 20 is supported, and Nabu and Java sources can be mixed in a single project.

## What works

- **Nabu can call Java** code and **Java can call Nabu** code.
- Java and Nabu sources can be mixed in one project.
- The compiler reads Java class files, including generics and modules, through its `resolve/asm` package (ASM 9.8). It runs against the class library of the JDK it runs on; JDK 25 is verified.
- Java **sources** (up to Java 20) are parsed with the ANTLR `Java20Parser.g4` grammar and the `JavaCompilerVisitor`, and compiled by Nabu.

## How Java sources are compiled

Nabu compiles Java sources itself, together with the Nabu sources, with whichever backend is selected. A `.java` file in a source directory of the [Maven plugin](/maven-plugin/) is parsed, resolved and compiled like a `.nabu` file, so Java and Nabu classes can refer to each other freely.

This works for substantial real-world code: picocli's single 19,000-line source file compiles with Nabu, and the result behaves like the official picocli jar. The [picocli example](/examples/picocli-demo/) uses the jar itself; for a native build the LLVM backend compiles the bytecode of the libraries on the classpath, so their sources are not needed.

If the `maven-compiler-plugin` also runs, `javac` compiles the Java sources a second time. That can be useful in an existing project that relies on `javac`; place the `nabu-maven-plugin` **before** the `maven-compiler-plugin`, so the Nabu classes exist when `javac` runs (see [Maven Plugin](/maven-plugin/)). To let Nabu compile everything on its own, disable `javac` for the main sources with `<skipMain>true</skipMain>`, as the picocli example does.

## Reference language level

The reference language level for the frontend is **Java 20** (JLS SE 20). Interop and mixed input languages support both Nabu and Java 20 features.

---
layout: page
title: Java Interop
---

The Nabu compiler provides **Java interoperability out of the box**. Java 20 is supported, and Nabu and Java sources can be mixed in a single project.

## What works

- **Nabu can call Java** code and **Java can call Nabu** code.
- Java and Nabu sources can be mixed in one project.
- The compiler reads Java class files, including generics and modules, through its `resolve/asm` package (ASM 9.8). It runs against the class library of the JDK it runs on; JDK 25 is verified.
- Java **sources** (up to Java 20) are parsed with the ANTLR `Java20Parser.g4` grammar and the `JavaCompilerVisitor`. By default Nabu only reads them, for their classes and methods, so that a `.nabu` file can use them; `javac` compiles them (see below).

## How Java sources are used

A **language parser** (the Nabu language) is a language that Nabu compiles. A **language support** (Java, Kotlin) is only there to read source files: Nabu parses a `.java` file to know its classes, methods and types, so that a `.nabu` file can call it, and it does not compile it. Compiling Java is left to `javac`. That is also what the native build does: [nabu-native-image]({{ site.baseurl }}/backends/llvm/native-image/) works on the bytecode that `javac` made.

So in a project with both languages, `javac` compiles the Java sources and Nabu the `.nabu` sources. A `.nabu` file can use Java classes directly from their sources. When Java code calls Nabu classes, place the `nabu-maven-plugin` **before** the `maven-compiler-plugin`, so the Nabu classes exist when `javac` runs (see [Maven Plugin]({{ site.baseurl }}/maven-plugin/)).

Nabu can still compile the Java sources of a language support itself, together with the Nabu sources and with whichever backend is selected: give the compiler the option `--compile-language-support` (in the Maven plugin: `<arg>--compile-language-support</arg>` in `compilerArgs`). Then a `.java` file in a source directory is parsed, resolved and compiled like a `.nabu` file. This works for substantial real-world code: picocli's single 19,000-line source file compiles that way, and the result behaves like the official picocli jar. To let Nabu compile everything on its own, also disable `javac` for the main sources with `<skipMain>true</skipMain>`, as the [picocli example]({{ site.baseurl }}/examples/picocli-demo/) does.

## Reference language level

The reference language level for the frontend is **Java 20** (JLS SE 20). Interop and mixed input languages support both Nabu and Java 20 features.

## Release

With `--release <version>` (Maven plugin: `release` or `maven.compiler.release`) the compiler checks the language level of the sources it compiles and writes class files for that version. The supported versions are 17 up to 25; another value is an error, and so is `--release` together with `--target`.

A language construct of a newer version than the release is an error, with the file and the line:

```
Pattern matching for switch requires Java 21, but the release is 17 (line 5)
```

What is checked, besides everything up to Java 17 (which is always allowed):

| Since | Construct |
|---|---|
| 21 | pattern matching for `switch` (with a `when` guard), `case null`, record patterns |
| 22 | unnamed variables and patterns (`_`) |
| 25 | statements before `this(...)` or `super(...)` in a constructor, `import module` |

The check covers the Java sources that Nabu compiles (`--compile-language-support`) and the `.nabu` sources, where a construct has the version of its Java counterpart and a construct without a counterpart is always allowed. The Java sources that are only read for their symbols are not checked, and neither is the use of JDK classes and methods: a class that a newer JDK added still compiles. Without `--release` nothing is checked.

The Java parser reads these constructs up to Java 25. Not everything that is read is compiled yet:

- A record pattern in `instanceof` works as the condition of an `if` statement (alone or as the first part of an `&&` chain), for example `if (o instanceof Line(Point(var x, _), var to) && x > 0)`. In another position, such as a `return` or a `while` condition, it is an error.
- `import module` is read and checked against the release, but compiling it is an error: "Module import declarations are not supported yet".
- Compact source files and instance `main` methods (Java 25) are not supported.

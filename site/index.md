---
layout: page
title: Introduction
---

The **Nabu compiler** is a polyglot compiler that compiles source code into Java bytecode, so it can run on the Java Virtual Machine (JVM). With its experimental LLVM backend it can also compile the same code to a **native executable**.

Writing a compiler is a complex task that takes a lot of time and usually requires a lot of expertise about the JVM. The goal of the Nabu compiler is to make this easier: it is designed to be **extendable**, so you can add support for your own language without having to write a compiler from scratch.

## What can you do with Nabu?

- **Compile the Nabu language** to JVM bytecode. Nabu is a programming language whose syntax is based on Java and Kotlin.
- **Use Java.** Nabu and Java sources can be mixed in a single project and call each other. The Java parser reads Java up to 25, and `--release` (17 to 25) checks the language level. By default `javac` compiles the Java sources; with `--compile-language-support` Nabu compiles them too, which it does for real-world Java libraries such as [picocli]({{ site.baseurl }}/examples/picocli-demo/).
- **Write DSLs.** The compiler supports domain-specific languages (DSLs) so you can write code that is more readable and maintainable for a specific domain. See [DSL support]({{ site.baseurl }}/plugins/dsl-support/) for an example with JPA.
- **Target multiple backends.** Besides the standard ASM backend (JVM bytecode), there are experimental backends for the JDK ClassFile API and for [LLVM]({{ site.baseurl }}/backends/llvm/), which produces native executables without a JVM.
- **Add your own language.** The compiler is extended via plugins. Provide a language parser that parses source code and builds an AST, and the compiler does the rest.

<div class="callout callout-note" markdown="1">
<b>Extendable by design.</b> The compiler defines extension points. Just provide a language parser, an element resolver, or a code transformer, and register it in a `plugin.xml` file.
</div>

## The simplest extension

Because the compiler is built around plugins, adding a language is largely about providing a `LanguageParser`:

```java
public class SimpleLanguageParser implements LanguageParser {

    private final FileObject.Kind SOURCE_KIND = new FileObject.Kind(".simple", true);

    @Override
    public FileObject.Kind getSourceKind() {
        return SOURCE_KIND;
    }

    @Override
    public CompilationUnit parse(final FileObject fileObject,
                                 final CompilerContext compilerContext) {
        // TODO: Implement parsing logic for .simple files
        return null;
    }
}
```

## Demo application

A demo application is available at the [Nabu petstore](https://github.com/potjerodekool/nabu-petstore) repository, showing the compiler in action.

## Getting started

To use Nabu and Java together in a Maven project, see the [Maven Plugin]({{ site.baseurl }}/maven-plugin/) documentation. For IDE support, check out the [Nabu Idea](https://github.com/potjerodekool/nabu-idea) plugin for IntelliJ.

To build native executables, install the [LLVM toolchain]({{ site.baseurl }}/backends/llvm/installation/), use [nabu-native-image]({{ site.baseurl }}/backends/llvm/native-image/) to compile a jar you already have, and follow the [picocli example]({{ site.baseurl }}/examples/picocli-demo/), which builds one program for the JVM, with Nabu's LLVM backend and with GraalVM. See [Known limitations]({{ site.baseurl }}/limitations/) for what does not work yet.

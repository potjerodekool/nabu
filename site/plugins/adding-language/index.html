---
layout: page
title: Adding a Language
---

To add support for a new language to the Nabu compiler, you provide a **language parser** that parses source files of your language and converts them into an abstract syntax tree (AST).

## The `LanguageParser` interface

```java
import io.github.potjerodekool.nabu.lang.spi.LanguageParser;

public class SimpleLanguageParser implements LanguageParser {

    private final FileObject.Kind SOURCE_KIND =
        new FileObject.Kind(".simple", true);

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

Key methods:

- **`getSourceKind()`** — returns the file extension `FileObject.Kind` that this parser handles (e.g. `.simple`).
- **`parse(...)`** — parses the source and builds a `CompilationUnit` (the AST root).

## Registering the parser

The language parser is registered in the plugin's `plugin.xml`:

```xml
<language-parser implementationClass="com.example.SimpleLanguageParser"/>
```

## What the compiler does for you

Once you produce a valid `CompilationUnit` AST, the rest of the pipeline — entering, resolving, transforming, checking, lowering, IR generation, optimization, and bytecode emission — is handled by the compiler. You do not need to write a compiler from scratch.

## Related interfaces

- `SourceParser` — the base interface (kind + `parse`)
- `LanguageParser` / `LanguageSupport` — extend `SourceParser` for full language support, including the `plugin.xml` `language-support` extension.

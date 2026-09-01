---
layout: page
title: Plugin System
---

The Nabu compiler is **extended through plugins**. A plugin declares extension points, and these extensions are registered in the plugin's `plugin.xml` file, discovered from the classpath and from a user directory.

## How plugins work

The `PluginRegistry` (in `compiler/src/main/java/io/github/potjerodekool/nabu/compiler/extension/`) is the top-level manager. It owns several sub-managers:

- `ExtensionManager` — named extensions for `element-resolver` and `code-transformer`
- `LanguageParserManager` — maps `FileObject.Kind` → `LanguageParser`
- `LanguageSupportManager` — maps to `LanguageSupport`
- `BackendManager` — resolves backends by name

Plugins are loaded via `registerPlugins()` which calls `loadPluginsFromClassPath()` and `loadPluginsFromUserDirectory()`. Each plugin's `plugin.xml` is parsed with SAX.

## A minimal plugin.xml

```xml
<?xml version="1.0" encoding="UTF-8" ?>
<plugin>
    <id>io.github.potjerodekool.nabu.plugin.simple</id>
    <version>0.0.1</version>
    <description>Simple plugin for Nabu compiler</description>
    <extensions>
    </extensions>
</plugin>
```

## Extension points

| Description | Interface | More |
|---|---|---|
| Custom element resolver for a DSL | `io.github.potjerodekool.nabu.resolve.spi.ElementResolver` | [More info](/plugins/dsl-support/) |
| Transformer to transform the AST (DSL AST → normal AST) | `io.github.potjerodekool.nabu.tools.transform.spi.CodeTransformer` | [More info](/plugins/dsl-support/) |
| Parser to parse source code and build an AST | `io.github.potjerodekool.nabu.lang.spi.LanguageParser` | [More info](/plugins/adding-language/) |
| Output backend | `io.github.potjerodekool.nabu.compiler.backend.Backend` | [More info](/backends/) |

## Plugin classloading

Plugins are loaded through a `PluginClassLoader` (a `URLClassLoader`). A `SharedLoader` aggregates plugin classloaders so classes can be found across plugins.

Extension declarations can constrain the JDK feature version via `supportsJdkFeatureVersion` (min/max) attributes.

## Example plugins

- **`jpa-plugin`** — a DSL plugin that transforms JPA criteria joins/casts into normal code. See [DSL Support](/plugins/dsl-support/).
- **`java-backend`** and **`native-backend`** — register the `JAVA` and `LLVM` backends. See [Backends](/backends/).

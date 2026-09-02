---
layout: page
title: Kotlin Support
---

The Nabu compiler has **experimental Kotlin support**.

<div class="callout callout-warning">
<b>Experimental.</b> Kotlin support is largely incomplete. Most constructs currently throw `UnsupportedOperationException`. It should not be relied upon for production use.
</div>

## Current state

- A `KotlinParser.g4` grammar and a `KotlinLanguageSupport` / `KotlinCompilerVisitor` exist.
- The visitor currently only handles **top-level class declarations**.
- Most other constructs are not yet implemented.

Kotlin is registered as a `language-support` extension in the compiler's `plugin.xml`.

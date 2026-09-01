---
layout: page
title: Element Resolver
---

The **element resolver** SPI (`ElementResolver`) lets a plugin provide custom resolution of elements within a DSL, so the compiler can understand DSL constructs that deviate from ordinary language rules.

## Interface

```java
package io.github.potjerodekool.nabu.resolve.spi;

public interface ElementResolver {
    boolean supports(Element element);
    // ... resolution hooks
}
```

## Purpose

A DSL typically introduces new ways of writing expressions (for example, field access on a casted join, or operator overloading). An `ElementResolver` teaches the compiler how to resolve these elements so they can be type-checked and transformed correctly.

It is paired with a `CodeTransformer`, which then rewrites the resolved DSL code into ordinary code during the [Transform phase](/compiler-design/). See [DSL Support](/plugins/dsl-support/) for the full example using JPA.

## Registration

```xml
<element-resolver implementationClass="io.github.potjerodekool.nabu.plugin.simple.transform.SimpleElementResolver"/>
```

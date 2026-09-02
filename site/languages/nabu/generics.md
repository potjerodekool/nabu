---
layout: page
title: Generics
---

Nabu supports full generic types, with JVM generic signatures emitted both ways.

## Parameterized classes

```java
public class Box<T> {

    private final value: T;

    public Box(value: T) {
        this.value = value;
    }

    public fun getValue(): T {
        return value;
    }
}
```

## Supported generic features

- **Parameterized types** — full signature support in both directions (via the ASM `SignatureParser` visitors)
- **Bounded type parameters** — `<T extends A & B>` via `typeBound` / `additionalBound`
- **Wildcards** — `? extends T` and `? super T` (`CWildcardType`)
- **Generic method invocation inference** — implemented through `UndetVarType` and `TypeMapFiller`/`TypeMapApplier`
- **`var`** — local type inference is supported

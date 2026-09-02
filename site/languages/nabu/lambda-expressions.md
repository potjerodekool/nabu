---
layout: page
title: Lambda Expressions
---

Lambda expressions in Nabu are desugared into synthetic private methods by the `LambdaToMethodPhase`, then called through SAM conversion (`invokedynamic` / `LambdaMetafactory`-style).

```java
public fun predicate(expected: String) {
    return (value : String) -> {
            return expected.equals(value);
    }
}
```

Method references are supported, including constructor references (`Type::new`).

Because lambdas are lowered in a dedicated phase before the backend, they work uniformly across all output backends.

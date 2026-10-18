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

Because lambdas are lowered in a dedicated phase before the backend, every backend sees the same synthetic methods. The JVM backends create the lambda objects with `invokedynamic` and `LambdaMetafactory`, in any context: as a method argument, in a variable or field, or as a return value. With the [LLVM backend](/backends/llvm/), lambdas inside compiled JDK code are supported; lambdas in your own source code have not been verified yet.

A lambda whose body is itself a lambda (`() -> () -> x`) is not supported yet; see [Known limitations](/limitations/).

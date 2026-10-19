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

Method references are supported in Java sources: static (`Math::max`), unbound (`String::length`), bound (`prefix::concat`) and constructor references (`ArrayList::new`). Once the target type is known (a method parameter, a variable or a return type), the resolver rewrites a method reference into the equivalent lambda, for example `String::length` into `(s) -> s.length()`. Unlike the JLS, the receiver expression of a bound reference is evaluated at each call rather than once.

Because lambdas are lowered in a dedicated phase before the backend, every backend sees the same synthetic methods. The JVM backends create the lambda objects with `invokedynamic` and `LambdaMetafactory`, in any context: as a method argument, in a variable or field, or as a return value. The [LLVM backend](/backends/llvm/) has no `LambdaMetafactory` at run time: `SourceLambdaLowering` generates the class that it would create (as for lambdas in compiled JDK bytecode), and the lambda expression becomes `new` of that class with the captured values.

A lambda whose body is itself a lambda (`() -> () -> x`, `a -> b -> a + b`) gets the inner functional interface as its target, and the inner lambda can capture the outer lambda's parameters.

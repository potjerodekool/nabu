---
layout: page
title: Exceptions
---

Methods can declare checked exceptions with a `throws` clause.

```java
public class Box<T> {

    private final value: T;

    public Box(value: T) {
        this.value = value;
    }

    public fun getValue(): T throws EmptyException {
        return value;
    }
}
```

## Exception handling

- **`throw` / `try` / `catch`** including **multi-catch** (`catchType: unannClassType ('|' classType)*`) are supported. Catch clauses emit an exception table via `TryCatchRange` metadata.
- **`finally`** blocks are supported (implemented per the Java 17 core roadmap).
- **try-with-resources** is supported, desugared to expanded variables with `close()` calls and suppressed-exception handling.
- `synchronized` methods and blocks emit `monitorenter` / `monitorexit`.

## Exception model across backends

- On the **ASM backend**, exceptions map to the JVM exception table.
- On the **LLVM backend**, exceptions are handled through the C runtime (`@nabu_throw`, `@nabu_catch` with landing pads and a personality function).

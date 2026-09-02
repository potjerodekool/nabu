---
layout: page
title: Classes
---

Nabu supports classes, interfaces, enums, and records, following a syntax inspired by Java and Kotlin.

## Classes

```java
package foo.bar.shape;

public class Rectangle extends AbstractShape implements Shape {
}
```

## Interfaces

```java
package foo.bar.shape;

public interface Shape {
    abstract fun points() : List<Point>;
}
```

Interfaces support `default`, `static`, and `private` methods.

## Enums

```java
package foo.bar.shape;

public enum Status {
    ON("On"),
    OFF("Off");

    private final text : String;

    private Status(final text : String) {
        this.text = text;
    }

    public fun getText() : String {
        return text;
    }
}
```

The compiler synthesises the `$VALUES`, `values()`, and `valueOf()` members automatically through the `EnumCodeGenerator`. Enum-constant bodies are supported.

## Records

```java
package foo.bar.shape;

public record IdAndName(id : String, name : String) {
}
```

Records support compact constructors, and the `RecordCodeGenerator` expands accessors automatically. The `ACC_RECORD` flag is emitted in the class file.

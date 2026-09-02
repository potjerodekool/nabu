---
layout: page
title: DSL Support
---

A **domain-specific language (DSL)** lets you write code more easily for a specific domain, for example XML, JSON, or JPA.

The DSL is a **compiler feature**, not a language feature, so it works independently of the language it is used in and can define its own rules.

## Implementing a DSL

To implement a DSL, one or more of these interfaces can be implemented:

| Interface | Description |
|---|---|
| `io.github.potjerodekool.nabu.compiler.resolve.spi.ElementResolver` | Resolves elements in a DSL |
| `io.github.potjerodekool.nabu.compiler.transform.CodeTransformer` | Transforms a DSL into regular code |

## Registering implementations

Implementations are registered in the `plugin.xml` file:

```xml
<?xml version="1.0" encoding="UTF-8" ?>
<plugin>
    <id>io.github.potjerodekool.nabu.plugin.simple</id>
    <version>0.0.1</version>
    <description>Simple plugin for Nabu compiler</description>
    <extensions>
        <code-transformer implementationClass="io.github.potjerodekool.nabu.plugin.simple.transform.SimpleTransformer"/>
        <element-resolver implementationClass="io.github.potjerodekool.nabu.plugin.simple.transform.SimpleElementResolver"/>
    </extensions>
</plugin>
```

## DSL example: JPA

Take, for example, JPA. Writing a predicate quickly results in hard-to-read code:

```java
fun findCompanyByEmployeeFirstName(employeeFirstName: String): Specification<Company> {
    return (c : Root<Company>, q: CriteriaQuery<?>, cb: CriteriaBuilder) -> {
        var e = (InnerJoin<Company, Employee>) c.employees;
        var e = c.join(Company_.employees, JoinType.INNER);
        return cb.equal(e.get(Employee_.FIRST_NAME), employeeFirstName);
    };
}
```

With a DSL you can write much more readable code:

```java
fun findCompanyByEmployeeFirstName(employeeFirstName: String): Specification<Company> {
    return (c : Root<Company>, q: CriteriaQuery<?>, cb: CriteriaBuilder) -> {
        var e = (InnerJoin<Company, Employee>) c.employees;
        return e.firstName == employeeFirstName;
    };
}
```

Here a join is defined with a cast using a DSL class (`InnerJoin` in this example), and the fields of the entity are accessed directly. Operator overloading is supported.

The `CodeTransformer` then rewrites this to standard code. Because the plugin has access to the type model, it can check whether fields exist:

```java
fun findCompanyByEmployeeFirstName(employeeFirstName: String): Specification<Company> {
    return (c : Root<Company>, q: CriteriaQuery<?>, cb: CriteriaBuilder) -> {
        var e = c.join("employees", JoinType.INNER);
        return cb.equal(e.get("firstName"), employeeFirstName);
    };
}
```

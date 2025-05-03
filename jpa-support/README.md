# JPA support

A library which contains classes to support some constructs in the
JPA DSL.

The io.github.potjerodekool.nabu.lang.jpa.support.JpaPredicate
is somewhat similar to Specification in Spring JPA.
In a Spring (boot) application you can just use Specification
and only need this library on during compilation.

    <dependency>
        <groupId>io.github.potjerodekool</groupId>
        <artifactId>nabu-jpa-support</artifactId>
        <version>1.0-SNAPSHOT</version>
        <scope>compile</scope>
    </dependency>
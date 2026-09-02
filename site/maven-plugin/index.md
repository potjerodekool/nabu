---
layout: page
title: Maven Plugin
---

The **Nabu Maven plugin** (`nabu-maven-plugin`) wires the Nabu compiler into your Maven build, so you can mix Nabu and Java sources in a single project.

## Modules

- `maven-plugin` — contains `CompileMojo`, `TestCompileMojo`, and `AbstractCompilerMojo`.

## Mixing Nabu and Java

To use Nabu and Java in the same project, the `nabu-maven-plugin` must be placed **before** the `maven-compiler-plugin`. The `build-helper-maven-plugin` adds `src/main/nabu` as a source directory.

```xml
<plugin>
    <groupId>org.codehaus.mojo</groupId>
    <artifactId>build-helper-maven-plugin</artifactId>
    <version>3.4.0</version>
    <executions>
        <execution>
            <id>add-source</id>
            <phase>generate-sources</phase>
            <goals>
                <goal>add-source</goal>
            </goals>
            <configuration>
                <sources>
                    <source>src/main/nabu</source>
                </sources>
            </configuration>
        </execution>
    </executions>
</plugin>
<plugin>
    <groupId>io.github.potjerodekool</groupId>
    <artifactId>nabu-maven-plugin</artifactId>
    <version>1.0-SNAPSHOT</version>
    <executions>
        <execution>
            <goals>
                <goal>compile</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

The `maven-compiler-plugin` is then configured so that its default compile/testCompile executions are disabled and Java compilation is split into separate phases that run after Nabu compilation:

```xml
<plugin>
    <artifactId>maven-compiler-plugin</artifactId>
    <version>3.13.0</version>
    <configuration>
        <release>${java.version}</release>
    </configuration>
    <executions>
        <execution>
            <id>default-compile</id>
            <phase>none</phase>
        </execution>
        <execution>
            <id>default-testCompile</id>
            <phase>none</phase>
        </execution>
        <execution>
            <id>java-compile</id>
            <phase>compile</phase>
            <goals>
                <goal>compile</goal>
            </goals>
        </execution>
        <execution>
            <id>java-test-compile</id>
            <phase>test-compile</phase>
            <goals>
                <goal>testCompile</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

This ordering ensures Nabu sources are compiled first, and are then available to the Java compiler via `javac` (which handles Java-to-bytecode).

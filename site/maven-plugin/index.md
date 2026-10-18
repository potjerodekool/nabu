---
layout: page
title: Maven Plugin
---

The **Nabu Maven plugin** (`nabu-maven-plugin`) wires the Nabu compiler into your Maven build, so you can mix Nabu and Java sources in a single project.

## Modules

- `maven-plugin` — contains `CompileMojo`, `TestCompileMojo`, and `AbstractCompilerMojo`.

The plugin is not published to Maven Central yet; build Nabu from source (`mvn install`) to get version `0.1.0-SNAPSHOT` into your local repository.

## Configuration

| Parameter | Property | Default | Meaning |
|---|---|---|---|
| `backend` | `nabu.backend` | `ASM` | `ASM`, `JAVA` or `LLVM` |
| `incremental` | `nabu.incremental` | `true` | Recompile only changed sources. The LLVM examples use `-Dnabu.incremental=false`. |
| `compilerArgs` | | | Extra compiler options, one per `<arg>`, written as `--name=value` or `--name:value` |
| `annotationProcessorPaths` | | | Annotation processors to run, as Maven coordinates (`groupId`, `artifactId`, `version`) |
| `generatedSourcesDirectory` | | `target/generated-sources/annotations` | Where annotation processors write generated sources |
| `targetVersion` | `maven.compiler.target` | `1.8` | Target Java version |
| `failOnError` | `maven.compiler.failOnError` | `true` | Fail the build on compilation errors |

The plugin compiles every source root of the project (`src/main/java` and any root added with `build-helper-maven-plugin`), both `.java` and `.nabu` files.

The compiler options for the LLVM backend (`--gc`, `--opt`, …) are described on the [LLVM backend](/backends/llvm/#compiler-options) page. For example:

```xml
<configuration>
    <compilerArgs>
        <arg>--backend=LLVM</arg>
        <arg>--gc=BOEHM</arg>
    </compilerArgs>
</configuration>
```

## Mixing Nabu and Java

Nabu compiles Java sources itself (see [Java Interop](/languages/java/)), so the simplest setup is to let the `nabu-maven-plugin` compile everything and switch off `javac` for the main sources:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <version>3.13.0</version>
    <configuration>
        <skipMain>true</skipMain>
    </configuration>
</plugin>
```

If you want `javac` to compile the Java sources as well, place the `nabu-maven-plugin` **before** the `maven-compiler-plugin`, as described below. The `build-helper-maven-plugin` adds `src/main/nabu` as a source directory.

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
    <version>0.1.0-SNAPSHOT</version>
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

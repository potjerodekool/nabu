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
| `targetVersion` | `maven.compiler.target` | `17` | Target Java version (17 up to 25; another value is an error) |
| `release` | `maven.compiler.release` | | Java version 17 to 25 for the language level and the class file version. A language feature of a newer version is an error (see [Release]({{ site.baseurl }}/languages/java/#release)); an unsupported value is an error too. Replaces `targetVersion` |
| `failOnError` | `maven.compiler.failOnError` | `true` | Fail the build on compilation errors |
| `daemon` | `nabu.daemon` | `false` | Compile with the Nabu compiler daemon, see [Compiler daemon](#compiler-daemon) |
| `daemonPort` | `nabu.daemon.port` | `9876` | Port of the daemon (on the loopback address only) |
| `daemonJar` | `nabu.daemon.jar` | `nabu-daemon` of the plugin version | The daemon jar |
| `daemonJvmArgs` | `nabu.daemon.jvmArgs` | | Arguments for the JVM of the daemon, separated by spaces (for example `-Xmx2g`) |

The plugin compiles every source root of the project (`src/main/java` and any root added with `build-helper-maven-plugin`), both `.java` and `.nabu` files.

The compiler options for the LLVM backend (`--gc`, `--opt`, …) are described on the [LLVM backend]({{ site.baseurl }}/backends/llvm/#compiler-options) page. For example:

```xml
<configuration>
    <compilerArgs>
        <arg>--backend=LLVM</arg>
        <arg>--gc=BOEHM</arg>
    </compilerArgs>
</configuration>
```

## Compiler daemon

Maven creates the class loader of a plugin again for every build, also in `mvnd`, so the compiler starts cold in every build: ANTLR builds the tables of the Java and Nabu grammars while it parses, and that takes seconds. With `-Dnabu.daemon=true` the plugin sends the compilation to the Nabu compiler daemon, a JVM that stays alive between builds and keeps the compiler warm. On the petstore demo (16 Java and 18 Nabu files) a warm build takes about 3.5 s instead of 7 s.

```
mvn compile -Dnabu.daemon=true
```

- The daemon is started by the first build that uses it, with the JVM of Maven, in `~/.nabu`. Its output is in `~/.nabu/daemon-<port>.log`. It stops itself after 30 minutes without a build (`-Dnabu.daemon.idleTimeoutMs=...` in `daemonJvmArgs`).
- It listens on `127.0.0.1` only, and compiles the paths it is given: do not forward the port.
- A daemon that is another build of the daemon jar than the one the plugin expects (also a rebuilt `SNAPSHOT`) is stopped and started again.
- When the daemon cannot be started or reached, the plugin warns and compiles in the process of Maven, as without the option.
- Messages the compiler writes to the standard output or error (for example the syntax errors of the parser) are in the log of the daemon, not in the output of Maven. Diagnostics of the compiler are in the output of Maven.
- The daemon holds the jars in `~/.nabu/plugins` open. Stop it (end the process, or wait for the idle timeout) before you rebuild those plugins.

## Mixing Nabu and Java

By default `javac` compiles the Java sources and the `nabu-maven-plugin` the Nabu sources; Nabu only reads the Java sources, for the classes a `.nabu` file uses (see [Java Interop]({{ site.baseurl }}/languages/java/)). To let the `nabu-maven-plugin` compile the Java sources as well, add `<arg>--compile-language-support</arg>` to its `compilerArgs`, and switch off `javac` for the main sources:

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

In the default setup, where `javac` compiles the Java sources, place the `nabu-maven-plugin` **before** the `maven-compiler-plugin`, as described below. The `build-helper-maven-plugin` adds `src/main/nabu` as a source directory.

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

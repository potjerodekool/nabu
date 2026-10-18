---
layout: page
title: "Example: a picocli command-line app"
---

This example builds one small command-line program, `greet`, in three ways from the same sources, and checks that all three behave identically:

| Build | How | Result |
|---|---|---|
| **llvm** | Nabu's [LLVM backend](/backends/llvm/), with picocli compiled from source | a native `greet.GreetCommand.exe` (46 MB) |
| **jvm** | Nabu's [ASM backend](/backends/asm/), with the normal picocli jar | class files for the JVM |
| **graal** | the same class files, turned into a native image by GraalVM `native-image` | a native `greet.exe` (13 MB) |

The program uses [picocli](https://picocli.info/) 4.7.6, a real-world library of about 19,000 lines in a single source file that leans heavily on annotations, reflection, generics, inner classes and collections. That makes it a good test for the compiler: in the llvm build Nabu compiles all of picocli and the JDK classes it uses.

## The program

`src/main/nabu/greet/GreetCommand.nabu`, written in the [Nabu language](/languages/nabu/):

```kotlin
package greet;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
        name = "greet",
        mixinStandardHelpOptions = true,
        version = {"greet 1.0"},
        description = {"Says hello to someone"}
)
public class GreetCommand implements Runnable {

    @Option(names = {"-n", "--name"}, description = {"Name to greet"})
    private name : String = "world";

    @Option(names = {"-c", "--count"}, description = {"Number of greetings"})
    private count : int = 1;

    fun run(): void {
        System.out.println("Hello " + this.name + ", " + this.count + " times");
    }

}
```

`src/main/nabu/greet/Main.java`, plain Java, compiled by Nabu as well:

```java
package greet;

import picocli.CommandLine;

public class Main {

    public static void main(String[] args) {
        System.exit(new CommandLine(new GreetCommand()).execute(args));
    }

}
```

The expected behavior:

```
$ greet
Hello world, 1 times

$ greet -n Ada -c 3
Hello Ada, 3 times

$ greet --version
greet 1.0

$ greet --help
Usage: greet [-hV] [-c=<count>] [-n=<name>]
Says hello to someone
  -c, --count=<count>   Number of greetings
  -h, --help            Show this help message and exit.
  -n, --name=<name>     Name to greet
  -V, --version         Print version information and exit.

$ greet --bogus            # exit code 2, output on stderr
Unknown option: '--bogus'
Usage: greet [-hV] [-c=<count>] [-n=<name>]
...
```

## Project layout

```
cli-demo/
├── pom.xml                       the three build profiles
├── verify.ps1                    builds and compares the three versions
├── src/main/nabu/greet/
│   ├── GreetCommand.nabu
│   └── Main.java
└── vendor/picocli-src/           picocli 4.7.6 sources (for the llvm build)
    └── picocli/CommandLine.java
```

## The build

### Common part: compiling with Nabu

The snippets below are slightly simplified from the demo's `pom.xml` (comments and a few compiler arguments for the symbol path are left out).

The base build compiles `src/main/nabu` with the [Nabu Maven plugin](/maven-plugin/). The plugin also runs picocli's annotation processor, `picocli-codegen`. That processor writes GraalVM reachability metadata (`META-INF/native-image/picocli-generated/reflect-config.json` and friends) for the annotated classes. Both native builds need this metadata: GraalVM for its own reflection, and Nabu's LLVM backend for its [reflection tables](/backends/llvm/#reflection).

```xml
<properties>
    <picocli.version>4.7.6</picocli.version>
    <nabu.version>0.1.0-SNAPSHOT</nabu.version>
</properties>

<dependencies>
    <dependency>
        <groupId>info.picocli</groupId>
        <artifactId>picocli</artifactId>
        <version>${picocli.version}</version>
    </dependency>
</dependencies>

<build>
    <plugins>
        <!-- src/main/nabu as source directory -->
        <plugin>
            <groupId>org.codehaus.mojo</groupId>
            <artifactId>build-helper-maven-plugin</artifactId>
            <version>3.4.0</version>
            <executions>
                <execution>
                    <id>add-source</id>
                    <phase>generate-sources</phase>
                    <goals><goal>add-source</goal></goals>
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
            <version>${nabu.version}</version>
            <configuration>
                <annotationProcessorPaths>
                    <path>
                        <groupId>info.picocli</groupId>
                        <artifactId>picocli-codegen</artifactId>
                        <version>${picocli.version}</version>
                    </path>
                </annotationProcessorPaths>
                <compilerArgs>
                    <arg>--backend=ASM</arg>
                </compilerArgs>
            </configuration>
            <executions>
                <execution>
                    <goals><goal>compile</goal></goals>
                </execution>
            </executions>
        </plugin>
        <!-- Nabu compiles everything, including Main.java -->
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-compiler-plugin</artifactId>
            <version>3.13.0</version>
            <configuration>
                <skipMain>true</skipMain>
            </configuration>
        </plugin>
    </plugins>
</build>
```

The demo's real `pom.xml` additionally runs the program once during `mvn test`, with `exec-maven-plugin` 3.6.4 and the goal `exec` (a separate JVM). The in-process goal `exec:java` would stop Maven itself, because `Main` calls `System.exit`.

### `-Pjvm`: on the JVM

The base build *is* the JVM build: Nabu's ASM backend writes class files, and the real picocli jar provides the library at run time. The `jvm` profile is empty and only exists so that all three builds can be selected the same way.

```xml
<profile>
    <id>jvm</id>
</profile>
```

Run it with the picocli jar on the classpath:

```powershell
java -cp "target\classes;$env:USERPROFILE\.m2\repository\info\picocli\picocli\4.7.6\picocli-4.7.6.jar" greet.Main --help
```

### `-Pgraal`: GraalVM native image

GraalVM's `native-maven-plugin` turns the class files from the JVM build, the picocli jar and the generated metadata into a native executable. This needs a GraalVM JDK (verified: GraalVM CE 25.4.4.1.1, pointed to by `GRAALVM_HOME`) and, on Windows, the MSVC build tools, which `native-image` finds by itself.

```xml
<profile>
    <id>graal</id>
    <build>
        <plugins>
            <plugin>
                <groupId>org.graalvm.buildtools</groupId>
                <artifactId>native-maven-plugin</artifactId>
                <version>1.1.14</version>
                <extensions>true</extensions>
                <executions>
                    <execution>
                        <id>build-native</id>
                        <phase>package</phase>
                        <goals><goal>compile-no-fork</goal></goals>
                    </execution>
                </executions>
                <configuration>
                    <imageName>greet</imageName>
                    <mainClass>greet.Main</mainClass>
                </configuration>
            </plugin>
        </plugins>
    </build>
</profile>
```

```powershell
$env:GRAALVM_HOME = "C:\programs\graalvm-ce-25.4.4.1.1"
mvn -Pgraal clean package
.\target\greet.exe --help
```

`native-image` takes under a minute for this program.

### `-Pllvm`: Nabu's LLVM backend

The llvm build compiles **picocli from source** together with the app: the `vendor/picocli-src` directory is added as an extra source root. The LLVM backend then compiles the JDK classes that picocli and the app use from bytecode. Regular expressions (used by picocli) are given as extra seeds.

```xml
<profile>
    <id>llvm</id>
    <build>
        <plugins>
            <plugin>
                <groupId>org.codehaus.mojo</groupId>
                <artifactId>build-helper-maven-plugin</artifactId>
                <version>3.4.0</version>
                <executions>
                    <execution>
                        <id>add-picocli-vendor</id>
                        <phase>generate-sources</phase>
                        <goals><goal>add-source</goal></goals>
                        <configuration>
                            <sources>
                                <source>${project.basedir}/vendor/picocli-src</source>
                            </sources>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
            <plugin>
                <groupId>io.github.potjerodekool</groupId>
                <artifactId>nabu-maven-plugin</artifactId>
                <version>${nabu.version}</version>
                <configuration>
                    <compilerArgs>
                        <arg>--backend=LLVM</arg>
                        <arg>--gc=BOEHM</arg>
                        <arg>--bytecode-classes=java.util.regex.Pattern,java.util.regex.Matcher</arg>
                    </compilerArgs>
                </configuration>
            </plugin>
        </plugins>
    </build>
</profile>
```

The `compilerArgs` of the profile replace those of the base build, so this build uses the LLVM backend instead of ASM. The [toolchain](/backends/llvm/installation/) has to be installed.

```powershell
mvn -Pllvm clean package -Dnabu.incremental=false
.\target\classes\greet.GreetCommand.exe --help
```

The build takes about ten minutes. Nabu compiles picocli and about 3,300 JDK classes, and writes a 150 MB `.ll` file next to the executable.

## Checking that all three agree

`verify.ps1` runs the five commands from [the program](#the-program) on each build and compares stdout, stderr and the exit code with the JVM build. With `-Build` it first builds every profile and copies the results to `dist\<profile>`, because every `mvn clean` wipes `target\`.

```powershell
.\verify.ps1 -Build              # build llvm, jvm and graal, then compare
.\verify.ps1                     # compare the builds already in dist\
.\verify.ps1 -Profiles jvm,llvm  # only these two
```

For each command the script starts the program with `TERM` and `OSTYPE` removed from the environment, because picocli turns ANSI colors on when `TERM` points to `xterm` (as in Git Bash). The core of the script:

```powershell
function Invoke-Greet([string] $file, [string[]] $arguments) {
    $info = New-Object System.Diagnostics.ProcessStartInfo
    $info.FileName = $file
    $info.Arguments = ($arguments | ForEach-Object {
        if ($_ -match '[\s"]') { '"' + ($_ -replace '"', '\"') + '"' } else { $_ }
    }) -join ' '
    $info.UseShellExecute = $false
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    [void] $info.Environment.Remove('TERM')
    [void] $info.Environment.Remove('OSTYPE')
    $process = [System.Diagnostics.Process]::Start($info)
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    $process.WaitForExit()
    [pscustomobject] @{
        Exit   = $process.ExitCode
        Stdout = $stdout.Result -replace "`r`n", "`n"
        Stderr = $stderr.Result -replace "`r`n", "`n"
    }
}

foreach ($command in $commands) {
    $expected = Invoke-Greet (Get-Invocation 'jvm' $command.Args) ...
    foreach ($profile in 'llvm', 'jvm', 'graal') {
        $actual = Invoke-Greet (Get-Invocation $profile $command.Args) ...
        $same = $actual.Exit -eq $expected.Exit -and
                $actual.Stdout -ceq $expected.Stdout -and
                $actual.Stderr -ceq $expected.Stderr
        ...
    }
}
```

`Get-Invocation` returns `java -classpath dist\jvm\classes;<picocli jar> greet.Main <args>` for the jvm build and `dist\<profile>\greet.exe <args>` for the native ones.

The result on the verification machine:

```
greet                llvm   exit=0   OK
greet                jvm    exit=0   OK
greet                graal  exit=0   OK
greet --help         llvm   exit=0   OK
greet --help         jvm    exit=0   OK
greet --help         graal  exit=0   OK
greet --version      llvm   exit=0   OK
greet --version      jvm    exit=0   OK
greet --version      graal  exit=0   OK
greet -n Ada -c 3    llvm   exit=0   OK
greet -n Ada -c 3    jvm    exit=0   OK
greet -n Ada -c 3    graal  exit=0   OK
greet --bogus        llvm   exit=2   OK
greet --bogus        jvm    exit=2   OK
greet --bogus        graal  exit=2   OK
Alle trajecten (llvm, jvm, graal) geven dezelfde output als jvm.
```

(The last line is the script's summary: "all builds give the same output as jvm".)

## What this exercises in the compiler

Getting picocli to run identically on all three targets required, among other things:

- Java 11+ **nestmates**: private members of nested classes accessed across classes (`NestHost`/`NestMembers`, `invokevirtual` for private methods of another class);
- **lambdas** in fields and local variables, generic self-typed builders (`Builder<T extends Builder<T>>`) and checkcasts for generic return types;
- **runtime-visible annotations** with enum and constant values and element defaults, read through reflection;
- in the native build: compiling the JDK's collections, `String.format` and regular expressions from bytecode, a reflection model from the generated metadata, and exceptions across the whole program.

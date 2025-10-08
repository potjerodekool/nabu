# Compiler for compiling code for the JVM.

The Nabu compiler is a polyglot compiler for compiling source code into Java bytecode so it can be executed on the Java Virtual Machine (JVM).

Writing a compiler is a complex task that that take a lot of time and usually requires a lot of expertise of the JVM.
The Nabu compiler is designed to be extendable so others can make use of it to support their own language
without having to write their own compiler from scratch.

The Nabu compiler has interoperability with other languages, currently it only supports Java.
It also allows you to write a DSL for your domain specific language (DSL) so you can write code that is more readable and maintainable.
A DSL can have other rules and support other features.

For example a DSL can be created for JPA where a join can be defined with a cast
and operator overloading is supported.

    fun findCompanyByEmployeeFirstName(employeeFirstName: String): JpaPredicate<Company> {
        return (c : Root<Company>, q: CriteriaQuery<?>, cb: CriteriaBuilder) -> {
            var e = (InnerJoin<Company, Employee>) c.employees;
            return e.firstName == employeeFirstName;
        };
    }

A DSL is implemented as a compiler plugin.

The Nabu compiler currently only compiles Nabu, a programming language which syntax is based on Java and Kotlin.

Nabu and Java can be mixed in one project
and Nabu code can call Java code and vice versa.
Java 20 is supported.

To use Nabu and Java in the same project the nabu-maven-plugin must be placed before the maven-compiler-plugin:

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

# IDE support

Intellij ([Nabu Idea](https://github.com/potjerodekool/nabu-idea))

# Documentation
[documentation](docs/index.html)

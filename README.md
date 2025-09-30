# Compiler for compiling code for the JVM.

The goal of this project is to explore new ways to write code for the JVM.
General-purpose languages are great but not everything can be made easy and readable to express
and you need a DSL. 

Take for example JPA with its type safe criteria API.
Its nice to have type save queries but it becomes hard to read and to maintain easyly.
Then you need a DSL thats allows you to write readable code.
But you also want it to work well with you general-purpose code and have no or less overhead.

Compilers are mosly just simple translaters, translate source code to something a computer can execute.
Wouldn't be nice if you could make the compiler smarter, that is knowns the diffent domains you are working on
like access a database read and write JSON?

With the Nabu compiler I will try to achieve this goal.
Currently the Nabu supports the Nabu programming language which syntax is currently a mix of Java and Kotlin.

In the future I have planes to make it expandable so others can make use of it to support there own language
without having to write there own compiler from scratch.
To have APIs for things like resolving classes (both classpath and modulepath)
and hava interoperability with Java.

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

# Extending the compiler
The Nabu compiler can be extended with plugins.
A plugin can interact with the compiler to modify the AST and create
a DSL.

The example below shows how an JPA predicate can be created with the JPA DSL.
An inner join is created by casting the employees collection of Company
and then the firstName can be compared.
As you can see both the cast and '==' operator are overloaded.

    fun findCompanyByEmployeeFirstName(employeeFirstName: String): JpaPredicate<Company> {
        return (c : Root<Company>, q: CriteriaQuery<?>, cb: CriteriaBuilder) -> {
            var e = (InnerJoin<Company, Employee>) c.employees;
            return e.firstName == employeeFirstName;
        };
    }

In normal Nabu code this would be written as:

    fun findCompanyByEmployeeFirstName(employeeFirstName: String): JpaPredicate<Company> {
        return (c : Root<Company>, q: CriteriaQuery<?>, cb: CriteriaBuilder) -> {
            var e = c.join(Company_.employees, JoinType.INNER);
            return cb.equal(e.get(Employee_.firstName),employeeFirstName);
        };
    }

The JPA knowns which operator operations make sence. And if you use a Root object which Entity it represents 
and which properties it has. 

# IDE support

Intellij ([Nabu Idea](https://github.com/potjerodekool/nabu-idea))

# Language Documentation
[Language documentation](docs/index.html)

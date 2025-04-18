# Compiler for the Nabu language

Nabu is a programming language that is similar to Java
and can be compiled for the JVM.

Nabu code can contain DSL elements to make code more readable and less verbose.

Nabu and Java can be mixed in one project
and Nabu code can call Java code and vice versa.
Java 20 is supported.
To do this the nabu-maven-plugin must be placed before the maven-compiler-plugin:

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
            return cb.equal(e.get(Employee_.firstName),employeeFirstName);;
        };
    }

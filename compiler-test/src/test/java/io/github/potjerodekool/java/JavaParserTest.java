package io.github.potjerodekool.java;

import io.github.potjerodekool.nabu.Java20Lexer;
import io.github.potjerodekool.nabu.Java20Parser;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Test;

public class JavaParserTest {

    @Test
    void test() {
        /*
        final var code = """
                public class SomeClass {
                    public void someMethod() {
                        final var person = new Person();
                        final var name = person.company.name;
                    }
                }""";
        */

        final var code = """
                person.company.name
                """;

        final var inputStream = CharStreams.fromString(code);
        final var lexer = new Java20Lexer(inputStream);
        final var tokens = new CommonTokenStream(lexer);
        final var parser = new Java20Parser(tokens);
        final var cu = parser.expressionName();
        System.out.println(cu);
    }
}

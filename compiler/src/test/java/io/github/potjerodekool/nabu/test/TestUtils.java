package io.github.potjerodekool.nabu.test;


import io.github.potjerodekool.nabu.compiler.InMemoryFileObject;
import io.github.potjerodekool.nabu.compiler.lang.support.java.Java20Lexer;
import io.github.potjerodekool.nabu.compiler.lang.support.java.Java20Parser;
import io.github.potjerodekool.nabu.compiler.lang.support.java.JavaCompilerVisitor;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Objects;
import java.util.function.Function;

/**
 * Test utilities.
 */
public final class TestUtils {

    private TestUtils() {
    }

    public static String readResource(final String name) {
        try (var input = TestUtils.class.getClassLoader().getResourceAsStream(name)) {
            return new String(input.readAllBytes());
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static String fixLines(final String text) {
        if (text == null || text.isEmpty()) {
            return text;
        } else {
            return text.replace("\r", "");
        }
    }

    public static <T> T parseJavaCode(final String code,
                                      final Function<Java20Parser, ParserRuleContext> parserInvoker) throws IOException {
        final var inputSteam = CharStreams.fromStream(new ByteArrayInputStream(code.getBytes()));
        final var lexer = new Java20Lexer(inputSteam);
        final var tokens = new CommonTokenStream(lexer);
        final var parser = new Java20Parser(tokens);
        final var tree = parserInvoker.apply(parser);

        final var visitor = new JavaCompilerVisitor(new InMemoryFileObject(code, "MyClass.java"));
        return (T) tree.accept(visitor);
    }

    public static Object parseType(final String type) throws IOException {
        final var inputSteam = CharStreams.fromStream(new ByteArrayInputStream(type.getBytes()));
        final var lexer = new Java20Lexer(inputSteam);
        final var tokens = new CommonTokenStream(lexer);
        final var parser = new Java20Parser(tokens);
        final var referenceType = parser.referenceType();

        return referenceType;
    }


}

package io.github.potjerodekool.nabu.test;

import io.github.potjerodekool.nabu.NabuLexer;
import io.github.potjerodekool.nabu.NabuParser;
import io.github.potjerodekool.nabu.compiler.Flags;
import io.github.potjerodekool.nabu.compiler.TreePrinter;
import io.github.potjerodekool.nabu.compiler.frontend.parser.NabuCompilerVisitor;
import io.github.potjerodekool.nabu.compiler.tree.Tree;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;

import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;

public final class TreeAssert {

    private TreeAssert() {
    }

    private static NabuParser createParser(final String code) {
        final var inputSteam = CharStreams.fromString(code);
        final var lexer = new NabuLexer(inputSteam);
        final var tokens = new CommonTokenStream(lexer);
        return new NabuParser(tokens);
    }

    public static void parseAndAssert(final String code,
                                      final Function<NabuParser, ParseTree> function1) {
        parseAndAssert(code, function1, "");
    }

    public static void parseAndAssert(final String code,
                                      final Function<NabuParser, ParseTree> function1,
                                      final String actualPrefix) {
        final var parser = createParser(code);
        final var visitor = new NabuCompilerVisitor(null);
        final var printer = new TreePrinter();
        final ParseTree functionResult = function1.apply(parser);
        final var visitorResult = functionResult.accept(visitor);
        final var actual = switch (visitorResult) {
            case Tree tree -> {
                tree.accept(printer, null);
                yield printer.getText();
            }
            case Integer flag -> flagToName(flag);
            case null, default -> throw new UnsupportedOperationException();
        };

        assertEquals(code, actualPrefix + actual);
    }

    private static String flagToName(final int flag) {
        return switch (flag) {
            case Flags.ABSTRACT -> "abstract";
            case Flags.PUBLIC -> "public";
            default -> throw new UnsupportedOperationException();
        };
    }

}

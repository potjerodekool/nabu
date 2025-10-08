package io.github.potjerodekool.nabu.test;

import io.github.potjerodekool.nabu.NabuLexer;
import io.github.potjerodekool.nabu.NabuParser;
import io.github.potjerodekool.nabu.compiler.ast.Flags;
import io.github.potjerodekool.nabu.compiler.TreePrinter;
import io.github.potjerodekool.nabu.compiler.frontend.parser.nabu.NabuCompilerVisitor;
import io.github.potjerodekool.nabu.compiler.io.FileObject;
import io.github.potjerodekool.nabu.compiler.io.NabuFileObject;
import io.github.potjerodekool.nabu.compiler.tree.Tree;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.ParseTree;

import java.nio.file.Paths;
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
        parseAndAssert(code, function1, actual -> actualPrefix + actual);
    }

    public static void parseAndAssert(final String code,
                                      final Function<NabuParser, ParseTree> function1,
                                      final Function<String, String> actualTransformer) {
        final var parser = createParser(code);
        final var fileObject = new NabuFileObject(
                new FileObject.Kind(".nabu", true),
                Paths.get("SomeClass.class")
        );

        final var visitor = new NabuCompilerVisitor(fileObject);
        final var printer = new TreePrinter();
        final ParseTree functionResult = function1.apply(parser);
        final var visitorResult = functionResult.accept(visitor);
        final var actual = switch (visitorResult) {
            case Tree tree -> {
                tree.accept(printer, null);
                yield printer.getText();
            }
            case Long flag -> flagToName(flag);
            case null, default -> throw new UnsupportedOperationException();
        };

        assertEquals(code, actualTransformer.apply(actual));
    }

    private static String flagToName(final long flag) {
        if (flag == Flags.ABSTRACT) {
            return "abstract";
        } else if (flag == Flags.PUBLIC) {
            return "public";
        } else {
            throw new UnsupportedOperationException();
        }
    }

}
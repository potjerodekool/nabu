package io.github.potjerodekool.nabu.test;

import io.github.potjerodekool.nabu.NabuLexer;
import io.github.potjerodekool.nabu.NabuParser;
import io.github.potjerodekool.nabu.compiler.TodoException;
import io.github.potjerodekool.nabu.compiler.TreePrinter;
import io.github.potjerodekool.nabu.compiler.frontend.parser.NabuCompilerVisitor;
import io.github.potjerodekool.nabu.compiler.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.compiler.tree.element.Function;
import io.github.potjerodekool.nabu.compiler.tree.element.CModifier;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.Statement;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;

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
                                      final java.util.function.Function<NabuParser, ParseTree> function1) {
        parseAndAssert(code, function1, "");
    }

    public static void parseAndAssert(final String code,
                                      final java.util.function.Function<NabuParser, ParseTree> function1,
                                      final String actualPrefix) {
        final var parser = createParser(code);
        final var visitor = new NabuCompilerVisitor(null);
        final var printer = new TreePrinter();
        final ParseTree functionResult = function1.apply(parser);
        final var visitorResult = functionResult.accept(visitor);
        String actual;

        switch (visitorResult) {
            case ExpressionTree expressionTree -> {
                expressionTree.accept(printer, null);
                actual = printer.getText();
            }
            case Statement statement -> {
                statement.accept(printer, null);
                actual = printer.getText();
            }
            case ClassDeclaration classDeclaration -> {
                classDeclaration.accept(printer, null);
                actual = printer.getText();
            }
            case CModifier modifier -> actual = modifier.name().toLowerCase();
            case Function function -> {
                function.accept(printer, null);
                actual = printer.getText();
            }
            case null, default -> throw new TodoException();
        }

        actual = actualPrefix + actual;
        assertEquals(code, actual);
    }

}

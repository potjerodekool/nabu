package io.github.potjerodekool.nabu.test;

import io.github.potjerodekool.nabu.lang.Flags;
import io.github.potjerodekool.nabu.tools.FileObject;
import io.github.potjerodekool.nabu.tools.PathFileObject;
import io.github.potjerodekool.nabu.tree.Tree;
import io.github.potjerodekool.nabu.testing.TreePrinter;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CodePointCharStream;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.tree.AbstractParseTreeVisitor;
import org.antlr.v4.runtime.tree.ParseTree;

import java.nio.file.Paths;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Base class for tree assert to test if code is correctly parsed.
 * @param <P> Parser type.
 */
public abstract class AbstractTreeAssert<P extends Parser> {

    private final String fileExtension;

    protected AbstractTreeAssert(final String fileExtension) {
        this.fileExtension = fileExtension;
    }

    protected abstract P createParser(final CodePointCharStream inputSteam);

    protected abstract AbstractParseTreeVisitor<?> createVisitor(final FileObject fileObject);

    public void parseAndAssert(final String code,
                               final Function<P, ParseTree> parseTreeBuilder) {
        parseAndAssert(code, parseTreeBuilder, "");
    }

    public void parseAndAssert(final String code,
                               final Function<P, ParseTree> parseTreeBuilder,
                               final String actualPrefix) {
        parseAndAssert(code, parseTreeBuilder, actual -> actualPrefix + actual);
    }

    public void parseAndAssert(final String code,
                               final Function<P, ParseTree> parseTreeBuilder,
                               final Function<String, String> actualTransformer) {
        final var parser = createParser(CharStreams.fromString(code));
        final var fileObject = new PathFileObject(
                new FileObject.Kind(fileExtension, true),
                Paths.get("SomeClass" + fileExtension)
        );

        final var visitor = createVisitor(fileObject);
        final var printer = new TreePrinter();
        final ParseTree functionResult = parseTreeBuilder.apply(parser);
        final var visitorResult = functionResult.accept(visitor);
        final var actual = switch (visitorResult) {
            case Tree tree -> {
                printer.acceptTree(tree, null);
                yield printer.getText();
            }
            case Long flag -> flagToName(flag);
            case null, default -> throw new UnsupportedOperationException();
        };

        assertEquals(code, actualTransformer.apply(actual));
    }

    private String flagToName(final long flag) {
        if (flag == Flags.ABSTRACT) {
            return "abstract";
        } else if (flag == Flags.PUBLIC) {
            return "public";
        } else {
            throw new UnsupportedOperationException();
        }
    }
}

package io.github.potjerodekool.nabu.compiler.backend.ir;

import io.github.potjerodekool.nabu.compiler.lang.support.java.Java20Lexer;
import io.github.potjerodekool.nabu.compiler.lang.support.java.Java20Parser;
import io.github.potjerodekool.nabu.compiler.lang.support.java.JavaCompilerVisitor;
import io.github.potjerodekool.nabu.testing.AbstractCompilerTest;
import io.github.potjerodekool.nabu.testing.InMemoryFileObject;
import io.github.potjerodekool.nabu.tools.FileObject;
import io.github.potjerodekool.nabu.tree.Tree;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;

import java.util.function.Function;

public abstract class BackendTest extends AbstractCompilerTest {

    protected <T extends Tree> T parse(final String code,
                                       final Function<Java20Parser, ParserRuleContext> function) {
        return parse(new InMemoryFileObject("", "Myclass.java"), code, function);
    }

    protected <T extends Tree> T parse(final FileObject fileObject,
                                       final String code,
                                       final Function<Java20Parser, ParserRuleContext> function) {
        final var inputSteam = CharStreams.fromString(code);
        final var lexer = new Java20Lexer(inputSteam);
        final var tokens = new CommonTokenStream(lexer);
        final var parser = new Java20Parser(tokens);

        final var context = function.apply(parser);
        final var visitor = new JavaCompilerVisitor(fileObject, false);
        return (T) context.accept(visitor);
    }

}

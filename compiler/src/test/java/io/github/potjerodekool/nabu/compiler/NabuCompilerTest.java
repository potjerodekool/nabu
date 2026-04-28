package io.github.potjerodekool.nabu.compiler;

import io.github.potjerodekool.nabu.compiler.impl.EnterPhase;
import io.github.potjerodekool.nabu.compiler.lang.support.java.Java20Lexer;
import io.github.potjerodekool.nabu.compiler.lang.support.java.Java20Parser;
import io.github.potjerodekool.nabu.compiler.lang.support.java.JavaCompilerVisitor;
import io.github.potjerodekool.nabu.compiler.resolve.impl.ResolverPhase;
import io.github.potjerodekool.nabu.testing.AbstractCompilerTest;
import io.github.potjerodekool.nabu.tools.FileObject;
import io.github.potjerodekool.nabu.tree.CompilationUnit;
import io.github.potjerodekool.nabu.tree.Tree;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;

import java.io.IOException;
import java.util.function.Function;

import static io.github.potjerodekool.nabu.compiler.backend.lower.Lower.lower;

public abstract class NabuCompilerTest extends AbstractCompilerTest {

    protected CompilationUnit process(final CompilationUnit cu,
                                      final CompilerPhase compilerPhase) {

        if (!shouldDoPhase(compilerPhase, CompilerPhase.ENTER)) {
            return cu;
        }

        EnterPhase.enterPhase(cu, getCompilerContext());

        if (!shouldDoPhase(compilerPhase, CompilerPhase.RESOLVE)) {
            return cu;
        }

        ResolverPhase.resolvePhase(cu, getCompilerContext());

        if (!shouldDoPhase(compilerPhase, CompilerPhase.LOWER)) {
            return cu;
        } else {
            return lower(cu, getCompilerContext());
        }
    }

    private boolean shouldDoPhase(final CompilerPhase compilerPhase,
                                  final CompilerPhase currentPhase) {
        return currentPhase.compareTo(compilerPhase) <= 0;
    }

    protected <T extends Tree> T parse(final FileObject fileObject,
                                       final Function<Java20Parser, ParserRuleContext> function) throws IOException {

        try (var input = fileObject.openInputStream()) {
            final var source = new String(input.readAllBytes());

            final var inputSteam = CharStreams.fromString(source);
            final var lexer = new Java20Lexer(inputSteam);
            final var tokens = new CommonTokenStream(lexer);
            final var parser = new Java20Parser(tokens);

            final var context = function.apply(parser);
            final var visitor = new JavaCompilerVisitor(fileObject, false);
            return (T) context.accept(visitor);
        }
    }
}

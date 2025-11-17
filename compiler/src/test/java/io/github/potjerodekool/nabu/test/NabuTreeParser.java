package io.github.potjerodekool.nabu.test;

import io.github.potjerodekool.nabu.NabuLexer;
import io.github.potjerodekool.nabu.NabuParser;
import io.github.potjerodekool.nabu.compiler.frontend.parser.nabu.NabuCompilerVisitor;
import io.github.potjerodekool.nabu.resolve.scope.Scope;
import io.github.potjerodekool.nabu.tools.CompilerContext;
import io.github.potjerodekool.nabu.tools.Constants;
import io.github.potjerodekool.nabu.tree.AbstractTreeVisitor;
import io.github.potjerodekool.nabu.tree.Tree;
import io.github.potjerodekool.nabu.tree.TreeUtils;
import io.github.potjerodekool.nabu.tree.expression.FieldAccessExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.tree.expression.LiteralExpressionTree;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CodePointCharStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;

import java.util.function.Function;

public class NabuTreeParser {

    private final CompilerContext compilerContext;
    private ParseTree parseTree;
    private Tree tree;

    private NabuTreeParser(final CompilerContext compilerContext) {
        this.compilerContext = compilerContext;
    }

    public Tree getTree() {
        return tree;
    }

    private CodePointCharStream createInputStream(final String code) {
        return CharStreams.fromString(code);
    }

    private NabuParser createParser(final CodePointCharStream inputSteam) {
        return new NabuParser(new CommonTokenStream(new NabuLexer(inputSteam)));
    }

    public static <T extends Tree> T parse(final String code,
                                           final Function<NabuParser, ParseTree> parseTreeCreator,
                                           final CompilerContext context,
                                           final Scope scope) {
        final var treeParser = new NabuTreeParser(context);
        final var parser = treeParser.createParser(treeParser.createInputStream(code));
        treeParser.parseTree = parseTreeCreator.apply(parser);
        treeParser.toAst(scope);
        return (T) treeParser.tree;
    }

    private void toAst(final Scope scope) {
        final var visitor = new NabuCompilerVisitor(null);
        final var result = (Tree) parseTree.accept(visitor);
        final var postProcessor = new PostProcessor(compilerContext);
        result.accept(postProcessor, scope);
        this.tree = result;
    }
}

class PostProcessor extends AbstractTreeVisitor<Tree, Scope> {

    private final CompilerContext compilerContext;

    PostProcessor(final CompilerContext compilerContext) {
        this.compilerContext = compilerContext;
    }

    @Override
    public Tree visitLiteralExpression(final LiteralExpressionTree literalExpression,
                                       final Scope scope) {
        if (literalExpression.getLiteralKind() == LiteralExpressionTree.Kind.STRING) {
            final var stringType = compilerContext.getClassElementLoader().loadClass(
                    null,
                    Constants.STRING
            ).asType();
            literalExpression.setType(stringType);
        }

        return super.visitLiteralExpression(literalExpression, scope);
    }

    @Override
    public Tree visitFieldAccessExpression(final FieldAccessExpressionTree fieldAccessExpression, final Scope scope) {
        final var name = TreeUtils.getClassName(fieldAccessExpression);

        final var clazz = compilerContext.getClassElementLoader().loadClass(
                null,
                name
        );

        if (clazz != null) {
            fieldAccessExpression.setType(clazz.asType());
            fieldAccessExpression.getField().setType(clazz.asType());
        }

        return super.visitFieldAccessExpression(fieldAccessExpression, scope);
    }

    @Override
    public Tree visitIdentifier(final IdentifierTree identifier, final Scope scope) {
        final var name = identifier.getName();
        final var symbol = scope.resolve(name);

        if (symbol != null) {
            identifier.setSymbol(symbol);
        }

        return super.visitIdentifier(identifier, scope);
    }
}
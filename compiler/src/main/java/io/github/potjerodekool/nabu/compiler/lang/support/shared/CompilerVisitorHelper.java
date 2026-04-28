package io.github.potjerodekool.nabu.compiler.lang.support.shared;

import io.github.potjerodekool.nabu.tools.Constants;
import io.github.potjerodekool.nabu.tree.Modifiers;
import io.github.potjerodekool.nabu.tree.Tree;
import io.github.potjerodekool.nabu.tree.TreeMaker;
import io.github.potjerodekool.nabu.tree.element.Kind;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.tree.expression.LiteralExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.MemberReference;
import io.github.potjerodekool.nabu.tree.expression.builder.MemberReferenceBuilder;
import io.github.potjerodekool.nabu.tree.statement.VariableDeclaratorTree;
import org.antlr.v4.runtime.ParserRuleContext;

import java.util.List;

public final class CompilerVisitorHelper {

    private CompilerVisitorHelper() {
    }

    public static MemberReference createMemberReference(final List<IdentifierTree> typeArguments,
                                                        final IdentifierTree identifier,
                                                        final ParserRuleContext ctx) {
        final IdentifierTree expression;
        final MemberReference.ReferenceKind mode;

        if (Constants.NEW.equals(identifier.getName())) {
            expression = TreeMaker.identifier(
                    Constants.INIT,
                    identifier.getLineNumber(),
                    identifier.getColumnNumber()
            );
            mode = MemberReference.ReferenceKind.NEW;
        } else {
            expression = identifier;
            mode = MemberReference.ReferenceKind.INVOKE;
        }


        return new MemberReferenceBuilder()
                .typeArguments(typeArguments)
                .expression(expression)
                .mode(mode)
                .lineNumber(ctx.getStart().getLine())
                .columnNumber(ctx.getStart().getCharPositionInLine())
                .build();
    }

    public static boolean isStringLiteral(final ExpressionTree expressionTree) {
        return expressionTree instanceof LiteralExpressionTree literalExpressionTree
                && literalExpressionTree.getLiteralKind() == LiteralExpressionTree.Kind.STRING;
    }

    public static LiteralExpressionTree mergeLiterals(final LiteralExpressionTree left,
                                                      final LiteralExpressionTree right) {
        final var leftValue = left.getLiteral().toString();
        final var rightValue = right.getLiteral().toString();
        return TreeMaker.literalExpressionTree(
                leftValue + rightValue,
                left.getLineNumber(),
                left.getColumnNumber()
        );
    }

    public static VariableDeclaratorTree toLambdaVariable(final Tree tree) {
        return TreeMaker.variableDeclarator(
                Kind.PARAMETER,
                new Modifiers(),
                null,
                (IdentifierTree) tree,
                null,
                null,
                tree.getLineNumber(),
                tree.getColumnNumber()
        );
    }
}

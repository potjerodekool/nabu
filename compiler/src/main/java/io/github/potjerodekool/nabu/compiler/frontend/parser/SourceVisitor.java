package io.github.potjerodekool.nabu.compiler.frontend.parser;

import io.github.potjerodekool.nabu.compiler.tree.CModifiers;
import io.github.potjerodekool.nabu.compiler.tree.TreeMaker;
import io.github.potjerodekool.nabu.compiler.tree.element.Function;
import io.github.potjerodekool.nabu.compiler.tree.element.Kind;
import io.github.potjerodekool.nabu.compiler.tree.element.builder.FunctionBuilder;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.FieldAccessExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.builder.FieldAccessExpressionBuilder;
import io.github.potjerodekool.nabu.compiler.tree.statement.BlockStatementTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.VariableDeclaratorTree;
import org.antlr.v4.runtime.ParserRuleContext;

import java.util.ArrayList;
import java.util.List;

public class SourceVisitor {

    public static Function createFunction(final CModifiers modifiers,
                                          final MethodHeader functionHeader,
                                          final BlockStatementTree body,
                                          final ParserRuleContext ctx) {
        var fullModifiers = modifiers;

        final var receiverParameter = functionHeader.functionDeclarator().receiverParameter();
        final var functionName = functionHeader.functionDeclarator().name();
        final var returnType = functionHeader.result();
        final var exceptions = functionHeader.exceptions();
        final List<VariableDeclaratorTree> params = functionHeader.functionDeclarator().parameters();

        if (!functionHeader.annotations().isEmpty()) {
            final var annotations = new ArrayList<>(modifiers.getAnnotations());
            annotations.addAll(functionHeader.annotations());
            fullModifiers = new CModifiers(
                    annotations,
                    modifiers.getFlags()
            );
        }

        return new FunctionBuilder()
                .lineNumber(ctx.getStart().getLine())
                .columnNumber(ctx.getStart().getCharPositionInLine())
                .kind(Kind.METHOD)
                .modifiers(fullModifiers)
                .typeParameters(functionHeader.typeParameters())
                .returnType(returnType)
                .simpleName(functionName)
                .receiver(receiverParameter)
                .parameters(params)
                .thrownTypes(exceptions)
                .body(body)
                .build();
    }

    public static FieldAccessExpressionTree processImportExpression(final ExpressionTree expressionTree) {
        if (expressionTree instanceof IdentifierTree identifierTree) {
            final var names = identifierTree.getName().split("\\.");

            if (names.length == 1) {
                return new FieldAccessExpressionBuilder()
                        .field(identifierTree)
                        .build();
            } else {
                ExpressionTree importExpression = TreeMaker.identifier(
                        names[0],
                        -1,
                        -1
                );

                for (int i = 1; i < names.length; i++) {
                    importExpression = new FieldAccessExpressionBuilder()
                            .selected(importExpression)
                            .field(
                                    TreeMaker.identifier(
                                            names[i],
                                            -1,
                                            -1
                                    )
                            )
                            .build();
                }

                return (FieldAccessExpressionTree) importExpression;
            }
        } else {
            return (FieldAccessExpressionTree) expressionTree;
        }
    }
}
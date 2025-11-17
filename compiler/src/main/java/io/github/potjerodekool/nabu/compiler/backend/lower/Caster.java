package io.github.potjerodekool.nabu.compiler.backend.lower;

import io.github.potjerodekool.nabu.tree.TreeMaker;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.tree.expression.MethodInvocationTree;
import io.github.potjerodekool.nabu.type.*;

public class Caster implements TypeVisitor<ExpressionTree, ExpressionTree> {

    @Override
    public ExpressionTree visitUnknownType(final TypeMirror typeMirror,
                                           final ExpressionTree expressionTree) {
        return expressionTree;
    }

    @Override
    public ExpressionTree visitPrimitiveType(final PrimitiveType primitiveType,
                                             final ExpressionTree expressionTree) {
        if (expressionTree instanceof MethodInvocationTree methodInvocationTree) {
            final var methodType = methodInvocationTree.getMethodType();
            final var methodTypeReturnType = methodType.getReturnType();

            if (methodTypeReturnType.getKind() == TypeKind.DECLARED) {
                final var methodReturnType = methodType
                        .getMethodSymbol()
                        .getReturnType();

                if (methodReturnType instanceof TypeVariable) {
                    final var className = methodTypeReturnType.asTypeElement().getQualifiedName();
                    final var identifier = IdentifierTree.create(className);
                    identifier.setType(methodTypeReturnType);

                    final var castExpression = TreeMaker.castExpressionTree(
                            identifier,
                            expressionTree,
                            -1,
                            -1
                    );
                    castExpression.setType(methodTypeReturnType);
                    return castExpression;
                }
            }
        }

        return expressionTree;
    }

    @Override
    public ExpressionTree visitDeclaredType(final DeclaredType declaredType,
                                            final ExpressionTree expressionTree) {
        return castIfNeeded(expressionTree);
    }

    @Override
    public ExpressionTree visitVariableType(final VariableType variableType,
                                            final ExpressionTree expressionTree) {
        return castIfNeeded(expressionTree);
    }

    private ExpressionTree castIfNeeded(final ExpressionTree expressionTree) {
        if (expressionTree instanceof MethodInvocationTree methodInvocationTree) {
            return castMethodInvocationIfNeeded(methodInvocationTree);
        }

        return expressionTree;
    }

    private ExpressionTree castMethodInvocationIfNeeded(final MethodInvocationTree methodInvocationTree) {
        final var methodType = methodInvocationTree.getMethodType();
        final var methodReturnType = methodType
                .getMethodSymbol()
                .getReturnType();

        if (methodReturnType instanceof TypeVariable) {
            final var methodTypeReturnType = methodInvocationTree.getMethodType().getReturnType();
            final var className = methodTypeReturnType.asTypeElement().getQualifiedName();
            final var identifier = IdentifierTree.create(className);
            identifier.setType(methodTypeReturnType);

            final var castExpression = TreeMaker.castExpressionTree(
                    identifier,
                    methodInvocationTree,
                    -1,
                    -1
            );
            castExpression.setType(methodTypeReturnType);
            return castExpression;
        }

        return methodInvocationTree;
    }
}

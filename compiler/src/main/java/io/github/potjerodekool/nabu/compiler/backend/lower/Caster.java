package io.github.potjerodekool.nabu.compiler.backend.lower;

import io.github.potjerodekool.nabu.compiler.ast.element.TypeElement;
import io.github.potjerodekool.nabu.compiler.tree.expression.CastExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.MethodInvocationTree;
import io.github.potjerodekool.nabu.compiler.type.*;

public class Caster implements TypeVisitor<ExpressionTree, ExpressionTree> {

    @Override
    public ExpressionTree visitUnknownType(final TypeMirror typeMirror, final ExpressionTree expressionTree) {
        return expressionTree;
    }

    @Override
    public ExpressionTree visitPrimitiveType(final PrimitiveType primitiveType, final ExpressionTree expressionTree) {
        if (expressionTree instanceof MethodInvocationTree methodInvocationTree) {
            final var methodType = methodInvocationTree.getMethodType();
            final var methodTypeReturnType = methodType.getReturnType();

            if (methodTypeReturnType.getKind() == TypeKind.DECLARED) {
                final var methodReturnType = methodType
                        .getMethodSymbol()
                        .getReturnType();

                if (methodReturnType instanceof TypeVariable) {
                    final var className = ((TypeElement) ((DeclaredType) methodTypeReturnType).asElement()).getQualifiedName();
                    final var identifier = new IdentifierTree(className);
                    identifier.setType(methodTypeReturnType);

                    final var castExpression = new CastExpressionTree()
                            .expression(expressionTree)
                            .targetType(identifier);
                    castExpression.setType(methodTypeReturnType);
                    return castExpression;
                }
            }
        }

        return expressionTree;
    }

}

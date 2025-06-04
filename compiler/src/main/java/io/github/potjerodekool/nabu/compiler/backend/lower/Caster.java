package io.github.potjerodekool.nabu.compiler.backend.lower;

import io.github.potjerodekool.nabu.compiler.ast.element.TypeElement;
import io.github.potjerodekool.nabu.compiler.tree.TreeMaker;
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
                    final var className = ((TypeElement) methodTypeReturnType.asElement()).getQualifiedName();
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

}

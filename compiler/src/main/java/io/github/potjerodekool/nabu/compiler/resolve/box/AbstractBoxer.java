package io.github.potjerodekool.nabu.compiler.resolve.box;

import io.github.potjerodekool.nabu.compiler.resolve.MethodResolver;
import io.github.potjerodekool.nabu.compiler.tree.TreeMaker;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.MethodInvocationTree;
import io.github.potjerodekool.nabu.compiler.type.PrimitiveType;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;

import java.util.List;

public abstract class AbstractBoxer {

    private final MethodResolver methodResolver;

    public AbstractBoxer(final MethodResolver methodResolver) {
        this.methodResolver = methodResolver;
    }

    public ExpressionTree boxer(final ExpressionTree expressionTree,
                                final TypeMirror targetType) {
        if (targetType instanceof PrimitiveType) {
            final var unboxed = unbox(expressionTree);

            if (unboxed instanceof MethodInvocationTree methodInvocation) {
                final var methodType = methodResolver.resolveMethod(methodInvocation, null);
                methodInvocation.setMethodType(methodType);
                return methodInvocation;
            } else {
                return unboxed;
            }
        } else {
            return expressionTree;
        }
    }

    protected abstract ExpressionTree unbox(final ExpressionTree expressionTree);

    protected ExpressionTree unbox(final ExpressionTree expressionTree,
                                   final String methodName) {
        return TreeMaker.methodInvocationTree(
                expressionTree,
                IdentifierTree.create(methodName),
                List.of(),
                List.of(),
                -1,
                -1
        );
    }
}

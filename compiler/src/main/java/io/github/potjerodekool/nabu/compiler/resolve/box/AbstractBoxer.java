package io.github.potjerodekool.nabu.compiler.resolve.box;

import io.github.potjerodekool.nabu.compiler.TodoException;
import io.github.potjerodekool.nabu.compiler.resolve.MethodResolver;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.MethodInvocationTree;
import io.github.potjerodekool.nabu.compiler.type.PrimitiveType;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;

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
                final var methodType = methodResolver.resolveMethod(methodInvocation);
                methodInvocation.setMethodType(methodType);
                return methodInvocation;
            } else {
                return unboxed;
            }
        } else {
            throw new TodoException();
        }
    }

    protected abstract ExpressionTree unbox(final ExpressionTree expressionTree);
}

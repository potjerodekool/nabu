package io.github.potjerodekool.nabu.compiler.resolve.box;

import io.github.potjerodekool.nabu.compiler.resolve.MethodResolver;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.MethodInvocationTree;

public class LongBoxer extends AbstractBoxer {

    public LongBoxer(final MethodResolver methodResolver) {
        super(methodResolver);
    }

    @Override
    protected ExpressionTree unbox(final ExpressionTree expressionTree) {
        final var methodInvocation = new MethodInvocationTree();
        return methodInvocation
                .target(expressionTree)
                .name(new IdentifierTree("longValue"));
    }

}

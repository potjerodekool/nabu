package io.github.potjerodekool.nabu.compiler.resolve.impl.box;

import io.github.potjerodekool.nabu.compiler.resolve.method.MethodResolver;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;

public class BooleanBoxer extends AbstractBoxer {

    public BooleanBoxer(final MethodResolver methodResolver) {
        super(methodResolver);
    }

    @Override
    protected ExpressionTree unbox(final ExpressionTree expressionTree) {
        return unbox(expressionTree, "booleanValue");
    }
}
package io.github.potjerodekool.nabu.compiler.resolve.box;

import io.github.potjerodekool.nabu.compiler.resolve.MethodResolver;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;

public class ShortBoxer extends AbstractBoxer {

    public ShortBoxer(final MethodResolver methodResolver) {
        super(methodResolver);
    }

    @Override
    protected ExpressionTree unbox(final ExpressionTree expressionTree) {
        return unbox(expressionTree, "shortValue");
    }
}

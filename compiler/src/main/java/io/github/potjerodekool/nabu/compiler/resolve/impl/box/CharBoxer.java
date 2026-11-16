package io.github.potjerodekool.nabu.compiler.resolve.impl.box;

import io.github.potjerodekool.nabu.compiler.resolve.method.MethodResolver;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;

public class CharBoxer extends AbstractBoxer {

    public CharBoxer(final MethodResolver methodResolver) {
        super(methodResolver);
    }

    @Override
    protected ExpressionTree unbox(final ExpressionTree expressionTree) {
        return unbox(expressionTree, "charValue");
    }
}
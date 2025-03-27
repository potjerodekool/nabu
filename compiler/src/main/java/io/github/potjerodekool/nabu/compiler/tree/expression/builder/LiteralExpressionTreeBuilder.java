package io.github.potjerodekool.nabu.compiler.tree.expression.builder;

import io.github.potjerodekool.nabu.compiler.tree.expression.LiteralExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.impl.CLiteralExpressionTree;

public class LiteralExpressionTreeBuilder extends ExpressionBuilder<LiteralExpressionTree, LiteralExpressionTreeBuilder> {

    private Object literal;

    public LiteralExpressionTreeBuilder(final LiteralExpressionTree literalExpressionTree) {
        super(literalExpressionTree);
        this.literal = literalExpressionTree.getLiteral();
    }

    @Override
    public LiteralExpressionTreeBuilder self() {
        return this;
    }

    public Object getLiteral() {
        return literal;
    }

    public LiteralExpressionTreeBuilder literal(final Object literal) {
        this.literal = literal;
        return this;
    }

    @Override
    public LiteralExpressionTree build() {
        return new CLiteralExpressionTree(this);
    }
}

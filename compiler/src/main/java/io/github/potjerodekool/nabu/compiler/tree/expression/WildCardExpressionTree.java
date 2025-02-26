package io.github.potjerodekool.nabu.compiler.tree.expression;

import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;

public class WildCardExpressionTree extends ExpressionTree {

    private final ExpressionTree extendsBound;
    private final ExpressionTree superBound;

    public WildCardExpressionTree(final ExpressionTree extendsBound,
                                  final ExpressionTree superBound) {
        this.extendsBound = extendsBound;
        this.superBound = superBound;
    }

    public ExpressionTree getExtendsBound() {
        return extendsBound;
    }

    public ExpressionTree getSuperBound() {
        return superBound;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitWildCardExpression(this, param);
    }
}

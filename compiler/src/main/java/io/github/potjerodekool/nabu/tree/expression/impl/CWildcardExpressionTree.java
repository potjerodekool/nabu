package io.github.potjerodekool.nabu.tree.expression.impl;

import io.github.potjerodekool.nabu.tree.TreeVisitor;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.WildcardExpressionTree;
import io.github.potjerodekool.nabu.type.BoundKind;

/**
 * Implementation of WildcardExpressionTree.
 */
public class CWildcardExpressionTree extends CExpressionTree implements WildcardExpressionTree {

    private final BoundKind boundKind;
    private final ExpressionTree bound;

    public CWildcardExpressionTree(final BoundKind boundKind,
                                   final ExpressionTree bound,
                                   final int lineNumber,
                                   final int columnNumber) {
        super(lineNumber, columnNumber);
        this.boundKind = boundKind;
        this.bound = bound;
    }

    public ExpressionTree getBound() {
        return bound;
    }

    public BoundKind getBoundKind() {
        return boundKind;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitWildCardExpression(this, param);
    }
}

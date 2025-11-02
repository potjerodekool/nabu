package io.github.potjerodekool.nabu.tree.expression.impl;

import io.github.potjerodekool.nabu.tree.TreeVisitor;
import io.github.potjerodekool.nabu.tree.expression.VariableTypeTree;

/**
 * Implementation of VariableTypeTree.
 */
public class CVariableTypeTree extends CExpressionTree implements VariableTypeTree {

    public CVariableTypeTree(final int lineNumber,
                             final int columnNumber) {
        super(lineNumber, columnNumber);
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitVariableType(this, param);
    }
}

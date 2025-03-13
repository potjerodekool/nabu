package io.github.potjerodekool.nabu.compiler.tree.expression.impl;

import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.expression.VariableTypeTree;

public class CVariableTypeTree extends CExpressionTree implements VariableTypeTree {

    public CVariableTypeTree(final int lineNumber,
                             final int charPositionInLine) {
        super(lineNumber, charPositionInLine);
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitVariableType(this, param);
    }
}

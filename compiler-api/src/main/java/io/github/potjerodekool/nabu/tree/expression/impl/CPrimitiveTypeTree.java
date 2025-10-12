package io.github.potjerodekool.nabu.tree.expression.impl;

import io.github.potjerodekool.nabu.tree.TreeVisitor;
import io.github.potjerodekool.nabu.tree.expression.PrimitiveTypeTree;

public class CPrimitiveTypeTree extends CExpressionTree implements PrimitiveTypeTree {

    private final Kind kind;

    public CPrimitiveTypeTree(final Kind kind,
                              final int lineNumber,
                              final int columnNumber) {
        super(lineNumber, columnNumber);
        this.kind = kind;
    }

    public Kind getKind() {
        return kind;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitPrimitiveType(this, param);
    }

}

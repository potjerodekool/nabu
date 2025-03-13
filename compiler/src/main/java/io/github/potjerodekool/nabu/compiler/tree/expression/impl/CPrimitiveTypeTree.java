package io.github.potjerodekool.nabu.compiler.tree.expression.impl;

import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.expression.PrimitiveTypeTree;

public class CPrimitiveTypeTree extends CExpressionTree implements PrimitiveTypeTree {

    private final Kind kind;

    public CPrimitiveTypeTree(final Kind kind,
                              final int lineNumber,
                              final int charPositionInLine) {
        super(lineNumber, charPositionInLine);
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

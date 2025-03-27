package io.github.potjerodekool.nabu.compiler.tree.statement.impl;

import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.statement.EmptyStatementTree;

public class CEmptyStatementTree extends CStatementTree implements EmptyStatementTree {

    public CEmptyStatementTree(final int lineNumber,
                               final int charPositionInLine) {
        super(lineNumber, charPositionInLine);
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitEmptyStatement(this, param);
    }
}

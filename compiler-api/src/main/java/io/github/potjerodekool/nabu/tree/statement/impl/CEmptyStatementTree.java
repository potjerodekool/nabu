package io.github.potjerodekool.nabu.tree.statement.impl;


import io.github.potjerodekool.nabu.tree.TreeVisitor;
import io.github.potjerodekool.nabu.tree.statement.EmptyStatementTree;
import io.github.potjerodekool.nabu.tree.statement.builder.StatementTreeBuilder;

/**
 * Implementation of EmptyStatement.
 */
public class CEmptyStatementTree extends CStatementTree implements EmptyStatementTree {

    public CEmptyStatementTree(final int lineNumber,
                               final int columnNumber) {
        super(lineNumber, columnNumber);
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitEmptyStatement(this, param);
    }

    @Override
    public StatementTreeBuilder<?> builder() {
        return new StatementTreeBuilder<>(this);
    }
}

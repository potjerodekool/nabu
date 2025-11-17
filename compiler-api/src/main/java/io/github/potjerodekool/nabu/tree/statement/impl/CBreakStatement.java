package io.github.potjerodekool.nabu.tree.statement.impl;


import io.github.potjerodekool.nabu.tree.Tree;
import io.github.potjerodekool.nabu.tree.TreeVisitor;
import io.github.potjerodekool.nabu.tree.statement.BreakStatement;
import io.github.potjerodekool.nabu.tree.statement.builder.StatementTreeBuilder;

/**
 * Implementation of BreakStatement.
 */
public class CBreakStatement extends CStatementTree implements BreakStatement {

    private final Tree target;

    public CBreakStatement(final Tree target,
                           final int lineNumber,
                           final int columnNumber) {
        super(lineNumber, columnNumber);
        this.target = target;
    }

    @Override
    public Tree getTarget() {
        return target;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitBreakStatement(this, param);
    }

    @Override
    public StatementTreeBuilder<?> builder() {
        return new StatementTreeBuilder<>(this);
    }
}

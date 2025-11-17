package io.github.potjerodekool.nabu.tree.statement.impl;


import io.github.potjerodekool.nabu.tree.Tree;
import io.github.potjerodekool.nabu.tree.TreeVisitor;
import io.github.potjerodekool.nabu.tree.statement.ContinueStatement;
import io.github.potjerodekool.nabu.tree.statement.builder.StatementTreeBuilder;

/**
 * Implementation of ContinueStatement.
 */
public class CContinueStatement extends CStatementTree implements ContinueStatement {

    private final Tree target;

    public CContinueStatement(final Tree target,
                              final int lineNumber,
                              final int columnNumber) {
        super(lineNumber, columnNumber);
        this.target = target;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitContinueStatement(this, param);
    }

    @Override
    public Tree getTarget() {
        return target;
    }

    @Override
    public StatementTreeBuilder<?> builder() {
        return new StatementTreeBuilder<>(this);
    }
}

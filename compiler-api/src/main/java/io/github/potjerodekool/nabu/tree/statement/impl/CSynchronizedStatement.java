package io.github.potjerodekool.nabu.tree.statement.impl;

import io.github.potjerodekool.nabu.tree.TreeVisitor;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.statement.BlockStatementTree;
import io.github.potjerodekool.nabu.tree.statement.SynchronizedStatement;
import io.github.potjerodekool.nabu.tree.statement.builder.StatementTreeBuilder;

/**
 * Implementation of SynchronizedStatement
 */
public class CSynchronizedStatement extends CStatementTree implements SynchronizedStatement {

    private final ExpressionTree expression;
    private final BlockStatementTree body;

    public CSynchronizedStatement(final ExpressionTree expression,
                                  final BlockStatementTree body,
                                  final int lineNumber,
                                  final int columnNumber) {
        super(lineNumber, columnNumber);
        this.expression = expression;
        this.body = body;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitSynchronizedStatement(this, param);
    }

    @Override
    public ExpressionTree getExpression() {
        return expression;
    }

    @Override
    public BlockStatementTree getBody() {
        return body;
    }

    @Override
    public StatementTreeBuilder<?> builder() {
        return new StatementTreeBuilder<>(this);
    }
}

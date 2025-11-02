package io.github.potjerodekool.nabu.tree.statement.impl;

import io.github.potjerodekool.nabu.tree.TreeVisitor;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.statement.ThrowStatement;
import io.github.potjerodekool.nabu.tree.statement.builder.StatementTreeBuilder;

/**
 * Implementation of ThrowStatement
 */
public class CThrowStatement extends CStatementTree implements ThrowStatement {

    private final ExpressionTree expression;

    public CThrowStatement(final ExpressionTree expression,
                           final int lineNumber,
                           final int columnNumber) {
        super(lineNumber, columnNumber);
        this.expression = expression;
    }

    @Override
    public ExpressionTree getExpression() {
        return expression;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitThrowStatement(this, param);
    }

    @Override
    public StatementTreeBuilder<?> builder() {
        return new StatementTreeBuilder(this);
    }
}

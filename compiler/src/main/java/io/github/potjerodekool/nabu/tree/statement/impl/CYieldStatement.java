package io.github.potjerodekool.nabu.tree.statement.impl;

import io.github.potjerodekool.nabu.tree.TreeVisitor;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.statement.YieldStatement;
import io.github.potjerodekool.nabu.tree.statement.builder.YieldStatementBuilder;

/**
 * Implementation of YieldStatement
 */
public final class CYieldStatement extends CStatementTree implements YieldStatement {

    private final ExpressionTree expression;

    public CYieldStatement(final ExpressionTree expression,
                           final int lineNumber,
                           final int columnNumber) {
        super(lineNumber, columnNumber);
        this.expression = expression;
    }

    public CYieldStatement(final YieldStatementBuilder yieldStatementBuilder) {
        super(yieldStatementBuilder);
        this.expression = yieldStatementBuilder.getExpression();
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitYieldStatement(this, param);
    }

    @Override
    public ExpressionTree getExpression() {
        return expression;
    }

    @Override
    public YieldStatementBuilder builder() {
        return new YieldStatementBuilder(this);
    }
}

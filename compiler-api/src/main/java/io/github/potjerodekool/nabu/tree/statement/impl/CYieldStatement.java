package io.github.potjerodekool.nabu.tree.statement.impl;

import io.github.potjerodekool.nabu.tree.TreeVisitor;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.statement.YieldStatement;

public class CYieldStatement extends CStatementTree implements YieldStatement {

    private final ExpressionTree expression;

    public CYieldStatement(final ExpressionTree expression,
                           final int lineNumber,
                           final int columnNumber) {
        super(lineNumber, columnNumber);
        this.expression = expression;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitYieldStatement(this, param);
    }

    @Override
    public ExpressionTree getExpression() {
        return expression;
    }
}

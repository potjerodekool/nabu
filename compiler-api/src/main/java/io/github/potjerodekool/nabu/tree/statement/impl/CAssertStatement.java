package io.github.potjerodekool.nabu.tree.statement.impl;

import io.github.potjerodekool.nabu.tree.TreeVisitor;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.statement.AssertStatement;

public class CAssertStatement extends CStatementTree implements AssertStatement {

    private final ExpressionTree condition;
    private final ExpressionTree detail;

    public CAssertStatement(final ExpressionTree condition,
                            final ExpressionTree detail,
                            final int lineNumber,
                            final int columnNumber) {
        super(lineNumber, columnNumber);
        this.condition = condition;
        this.detail = detail;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitAssertStatement(this, param);
    }

    @Override
    public ExpressionTree getCondition() {
        return condition;
    }

    @Override
    public ExpressionTree getDetail() {
        return detail;
    }
}

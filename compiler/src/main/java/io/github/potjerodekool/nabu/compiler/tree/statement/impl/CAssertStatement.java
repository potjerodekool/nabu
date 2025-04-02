package io.github.potjerodekool.nabu.compiler.tree.statement.impl;

import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.AssertStatement;

public class CAssertStatement extends CStatementTree implements AssertStatement {

    private final ExpressionTree condition;
    private final ExpressionTree detail;

    public CAssertStatement(final ExpressionTree condition,
                            final ExpressionTree detail,
                            final int lineNumber,
                            final int charPositionInLine) {
        super(lineNumber, charPositionInLine);
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

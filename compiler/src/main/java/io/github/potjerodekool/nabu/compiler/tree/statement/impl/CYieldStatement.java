package io.github.potjerodekool.nabu.compiler.tree.statement.impl;

import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.YieldStatement;

public class CYieldStatement extends CStatementTree implements YieldStatement {

    private final ExpressionTree expression;

    public CYieldStatement(final ExpressionTree expression,
                           final int lineNumber,
                           final int charPositionInLine) {
        super(lineNumber, charPositionInLine);
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

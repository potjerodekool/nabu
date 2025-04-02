package io.github.potjerodekool.nabu.compiler.tree.statement.impl;


import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.ThrowStatement;

public class CThrowStatement extends CStatementTree implements ThrowStatement {

    private final ExpressionTree expression;

    public CThrowStatement(final ExpressionTree expression,
                           final int lineNumber,
                           final int charPositionInLine) {
        super(lineNumber, charPositionInLine);
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
}

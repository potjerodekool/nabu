package io.github.potjerodekool.nabu.compiler.tree.statement.impl;

import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.ReturnStatement;
import io.github.potjerodekool.nabu.compiler.tree.statement.ReturnStatementBuilder;

import java.util.Objects;

public class CReturnStatement extends CStatement implements ReturnStatement {

    private ExpressionTree expression;

    public CReturnStatement(final ExpressionTree expression,
                            final int lineNumber,
                            final int charPositionInLine) {
        super(lineNumber, charPositionInLine);
        this.expression = expression;
    }

    public CReturnStatement(final ReturnStatementBuilder builder) {
        super(builder);
        this.expression = builder.getExpression();
    }

    public ExpressionTree getExpression() {
        return expression;
    }

    public CReturnStatement expression(final ExpressionTree expression) {
        Objects.requireNonNull(expression);
        this.expression = expression;
        return this;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitReturnStatement(this, param);
    }

    @Override
    public ReturnStatementBuilder builder() {
        return new ReturnStatementBuilder(this);
    }

}

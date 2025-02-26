package io.github.potjerodekool.nabu.compiler.tree.statement;

import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;

import java.util.Objects;

public class ReturnStatement extends Statement {

    private ExpressionTree expression;

    public ReturnStatement() {
    }

    public ReturnStatement(final ExpressionTree expression) {
        this.expression = expression;
    }

    protected ReturnStatement(final ReturnStatementBuilder builder) {
        super(builder);
        this.expression = builder.expression;
    }

    public ExpressionTree getExpression() {
        return expression;
    }

    public ReturnStatement expression(final ExpressionTree expression) {
        Objects.requireNonNull(expression);
        this.expression = expression;
        return this;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitReturnStatement(this, param);
    }

    public ReturnStatementBuilder builder() {
        return new ReturnStatementBuilder(this);
    }

}

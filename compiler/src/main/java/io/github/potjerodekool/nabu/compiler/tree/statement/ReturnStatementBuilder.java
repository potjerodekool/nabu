package io.github.potjerodekool.nabu.compiler.tree.statement;

import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;

public class ReturnStatementBuilder extends StatementBuilder<ReturnStatement> {

    ExpressionTree expression;

    protected ReturnStatementBuilder(final ReturnStatement original) {
        super(original);
        this.expression = original.getExpression();
    }

    @Override
    public ReturnStatementBuilder self() {
        return this;
    }

    public ReturnStatementBuilder expression(final ExpressionTree expression) {
        this.expression = expression;
        return this;
    }

    @Override
    public ReturnStatement build() {
        return new ReturnStatement(this);
    }
}

package io.github.potjerodekool.nabu.compiler.tree.statement;

import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.impl.CReturnStatement;

public class ReturnStatementBuilder extends StatementBuilder<ReturnStatement, ReturnStatementBuilder> {

    private ExpressionTree expression;

    public ReturnStatementBuilder() {
        super();
    }


    public ReturnStatementBuilder(final ReturnStatement original) {
        super(original);
        this.expression = original.getExpression();
    }

    public ExpressionTree getExpression() {
        return expression;
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
        return new CReturnStatement(this);
    }
}

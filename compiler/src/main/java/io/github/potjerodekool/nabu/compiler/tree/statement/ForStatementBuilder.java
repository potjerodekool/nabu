package io.github.potjerodekool.nabu.compiler.tree.statement;

import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;

public class ForStatementBuilder extends StatementBuilder<ForStatement> {

    Statement forInit;
    ExpressionTree expression;
    ExpressionTree forUpdate;
    Statement statement;

    protected ForStatementBuilder(final ForStatement original) {
        super(original);
        this.forInit = original.getForInit();
        this.expression = original.getExpression();
        this.forUpdate = original.getForUpdate();
        this.statement = original.getStatement();
    }

    @Override
    public ForStatementBuilder self() {
        return null;
    }

    @Override
    public ForStatement build() {
        return new ForStatement(this);
    }

    public ForStatementBuilder forInit(final Statement forInit) {
        this.forInit = forInit;
        return this;
    }

    public ForStatementBuilder expression(final ExpressionTree expression) {
        this.expression = expression;
        return this;
    }

    public ForStatementBuilder forUpdate(final ExpressionTree forUpdate) {
        this.forUpdate = forUpdate;
        return this;
    }

    public ForStatementBuilder statement(final Statement statement) {
        this.statement = statement;
        return this;
    }
}

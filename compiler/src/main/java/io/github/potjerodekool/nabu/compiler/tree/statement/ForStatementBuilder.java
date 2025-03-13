package io.github.potjerodekool.nabu.compiler.tree.statement;

import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.impl.CForStatement;

public class ForStatementBuilder extends StatementBuilder<ForStatement, ForStatementBuilder> {

    private Statement forInit;
    private ExpressionTree expression;
    private ExpressionTree forUpdate;
    private Statement statement;

    public ForStatementBuilder(final ForStatement original) {
        super(original);
        this.forInit = original.getForInit();
        this.expression = original.getExpression();
        this.forUpdate = original.getForUpdate();
        this.statement = original.getStatement();
    }

    public Statement getForInit() {
        return forInit;
    }

    public ExpressionTree getExpression() {
        return expression;
    }

    public ExpressionTree getForUpdate() {
        return forUpdate;
    }

    public Statement getStatement() {
        return statement;
    }

    @Override
    public ForStatementBuilder self() {
        return null;
    }

    @Override
    public ForStatement build() {
        return new CForStatement(this);
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

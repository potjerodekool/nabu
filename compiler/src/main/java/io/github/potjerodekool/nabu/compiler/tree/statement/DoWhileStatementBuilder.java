package io.github.potjerodekool.nabu.compiler.tree.statement;

import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.impl.CDoWhileStatement;

public class DoWhileStatementBuilder extends StatementBuilder<DoWhileStatement, DoWhileStatementBuilder> {

    private Statement body;
    private ExpressionTree condition;

    public DoWhileStatementBuilder(final DoWhileStatement original) {
        super(original);
        this.body = original.getBody();
        this.condition = original.getCondition();
    }

    public Statement getBody() {
        return body;
    }

    public ExpressionTree getCondition() {
        return condition;
    }

    @Override
    public DoWhileStatementBuilder self() {
        return null;
    }

    public DoWhileStatementBuilder condition(final ExpressionTree condition) {
        this.condition = condition;
        return this;
    }

    public DoWhileStatementBuilder body(final Statement body) {
        this.body = body;
        return this;
    }

    @Override
    public DoWhileStatement build() {
        return new CDoWhileStatement(this);
    }
}

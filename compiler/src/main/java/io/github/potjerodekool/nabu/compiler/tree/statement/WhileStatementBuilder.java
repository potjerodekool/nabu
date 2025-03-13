package io.github.potjerodekool.nabu.compiler.tree.statement;

import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.impl.CWhileStatement;

public class WhileStatementBuilder extends StatementBuilder<WhileStatement, WhileStatementBuilder> {

    private ExpressionTree condition;
    private Statement body;

    public WhileStatementBuilder(final WhileStatement original) {
        super(original);
        this.condition = original.getCondition();
        this.body = original.getBody();
    }

    public ExpressionTree getCondition() {
        return condition;
    }

    public Statement getBody() {
        return body;
    }

    @Override
    public WhileStatementBuilder self() {
        return this;
    }

    public WhileStatementBuilder condition(final ExpressionTree condition) {
        this.condition = condition;
        return this;
    }

    public WhileStatementBuilder body(final Statement body) {
        this.body = body;
        return this;
    }

    @Override
    public WhileStatement build() {
        return new CWhileStatement(this);
    }
}

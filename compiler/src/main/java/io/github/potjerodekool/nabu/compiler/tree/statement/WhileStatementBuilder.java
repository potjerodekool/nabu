package io.github.potjerodekool.nabu.compiler.tree.statement;

import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;

public class WhileStatementBuilder extends StatementBuilder<WhileStatement> {

    ExpressionTree condition;
    Statement body;

    protected WhileStatementBuilder(final WhileStatement original) {
        super(original);
        this.condition = original.condition;
        this.body = original.body;
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
        return new WhileStatement(this);
    }
}

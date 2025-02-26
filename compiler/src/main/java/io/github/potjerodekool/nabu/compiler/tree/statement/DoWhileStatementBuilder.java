package io.github.potjerodekool.nabu.compiler.tree.statement;

import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;

public class DoWhileStatementBuilder extends StatementBuilder<DoWhileStatement> {

    protected Statement body;
    protected ExpressionTree condition;

    protected DoWhileStatementBuilder(final DoWhileStatement original) {
        super(original);
        this.body = original.body;
        this.condition = original.condition;
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
        return new DoWhileStatement(this);
    }
}

package io.github.potjerodekool.nabu.compiler.tree.statement.builder;

import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.StatementTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.WhileStatementTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.impl.CWhileStatementTree;

public class WhileStatementTreeBuilder extends StatementTreeBuilder<WhileStatementTree, WhileStatementTreeBuilder> {

    private ExpressionTree condition;
    private StatementTree body;

    public WhileStatementTreeBuilder(final WhileStatementTree original) {
        super(original);
        this.condition = original.getCondition();
        this.body = original.getBody();
    }

    public ExpressionTree getCondition() {
        return condition;
    }

    public StatementTree getBody() {
        return body;
    }

    @Override
    public WhileStatementTreeBuilder self() {
        return this;
    }

    public WhileStatementTreeBuilder condition(final ExpressionTree condition) {
        this.condition = condition;
        return this;
    }

    public WhileStatementTreeBuilder body(final StatementTree body) {
        this.body = body;
        return this;
    }

    @Override
    public WhileStatementTree build() {
        return new CWhileStatementTree(this);
    }
}

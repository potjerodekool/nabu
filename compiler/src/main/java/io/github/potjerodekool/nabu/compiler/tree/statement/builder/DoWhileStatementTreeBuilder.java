package io.github.potjerodekool.nabu.compiler.tree.statement.builder;

import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.DoWhileStatementTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.StatementTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.impl.CDoWhileStatementTree;

public class DoWhileStatementTreeBuilder extends StatementTreeBuilder<DoWhileStatementTree, DoWhileStatementTreeBuilder> {

    private StatementTree body;
    private ExpressionTree condition;

    public DoWhileStatementTreeBuilder(final DoWhileStatementTree original) {
        super(original);
        this.body = original.getBody();
        this.condition = original.getCondition();
    }

    public StatementTree getBody() {
        return body;
    }

    public ExpressionTree getCondition() {
        return condition;
    }

    @Override
    public DoWhileStatementTreeBuilder self() {
        return null;
    }

    public DoWhileStatementTreeBuilder condition(final ExpressionTree condition) {
        this.condition = condition;
        return this;
    }

    public DoWhileStatementTreeBuilder body(final StatementTree body) {
        this.body = body;
        return this;
    }

    @Override
    public DoWhileStatementTree build() {
        return new CDoWhileStatementTree(this);
    }
}

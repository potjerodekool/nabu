package io.github.potjerodekool.nabu.tree.statement.impl;

import io.github.potjerodekool.nabu.tree.TreeVisitor;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.statement.StatementTree;
import io.github.potjerodekool.nabu.tree.statement.WhileStatementTree;
import io.github.potjerodekool.nabu.tree.statement.builder.WhileStatementTreeBuilder;

public class CWhileStatementTree extends CStatementTree implements WhileStatementTree {

    final ExpressionTree condition;
    final StatementTree body;

    public CWhileStatementTree(final ExpressionTree condition,
                               final StatementTree body,
                               final int lineNumber,
                               final int columnNumber) {
        super(lineNumber, columnNumber);
        this.condition = condition;
        this.body = body;
    }

    public CWhileStatementTree(final WhileStatementTreeBuilder whileStatementTreeBuilder) {
        super(whileStatementTreeBuilder);
        this.condition = whileStatementTreeBuilder.getCondition();
        this.body = whileStatementTreeBuilder.getBody();
    }

    public ExpressionTree getCondition() {
        return condition;
    }

    public StatementTree getBody() {
        return body;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitWhileStatement(this, param);
    }

    @Override
    public WhileStatementTreeBuilder builder() {
        return new WhileStatementTreeBuilder(this);
    }

}

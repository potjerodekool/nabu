package io.github.potjerodekool.nabu.tree.statement.impl;

import io.github.potjerodekool.nabu.tree.TreeVisitor;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.statement.DoWhileStatementTree;
import io.github.potjerodekool.nabu.tree.statement.StatementTree;
import io.github.potjerodekool.nabu.tree.statement.builder.DoWhileStatementTreeBuilder;

/**
 * Implementation of DoWhileStatement.
 */
public class CDoWhileStatementTree extends CStatementTree implements DoWhileStatementTree {

    final StatementTree body;
    final ExpressionTree condition;

    public CDoWhileStatementTree(final StatementTree body,
                                 final ExpressionTree condition,
                                 final int lineNumber,
                                 final int columnNumber) {
        super(lineNumber, columnNumber);
        this.body = body;
        this.condition = condition;
    }

    public CDoWhileStatementTree(final DoWhileStatementTreeBuilder doWhileStatementTreeBuilder) {
        super(doWhileStatementTreeBuilder);
        this.body = doWhileStatementTreeBuilder.getBody();
        this.condition = doWhileStatementTreeBuilder.getCondition();
    }

    public StatementTree getBody() {
        return body;
    }

    public ExpressionTree getCondition() {
        return condition;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitDoWhileStatement(this, param);
    }

    @Override
    public DoWhileStatementTreeBuilder builder() {
        return new DoWhileStatementTreeBuilder(this);
    }

}

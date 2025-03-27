package io.github.potjerodekool.nabu.compiler.tree.statement.impl;

import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.DoWhileStatementTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.builder.DoWhileStatementTreeBuilder;
import io.github.potjerodekool.nabu.compiler.tree.statement.StatementTree;

public class CDoWhileStatementTree extends CStatementTree implements DoWhileStatementTree {

    final StatementTree body;
    final ExpressionTree condition;

    public CDoWhileStatementTree(final StatementTree body,
                                 final ExpressionTree condition,
                                 final int lineNumber,
                                 final int charPositionInLine) {
        super(lineNumber, charPositionInLine);
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

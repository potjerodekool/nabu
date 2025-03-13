package io.github.potjerodekool.nabu.compiler.tree.statement.impl;

import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.DoWhileStatement;
import io.github.potjerodekool.nabu.compiler.tree.statement.DoWhileStatementBuilder;
import io.github.potjerodekool.nabu.compiler.tree.statement.Statement;

public class CDoWhileStatement extends CStatement implements DoWhileStatement {

    final Statement body;
    final ExpressionTree condition;

    public CDoWhileStatement(final Statement body,
                             final ExpressionTree condition,
                             final int lineNumber,
                             final int charPositionInLine) {
        super(lineNumber, charPositionInLine);
        this.body = body;
        this.condition = condition;
    }

    public CDoWhileStatement(final DoWhileStatementBuilder doWhileStatementBuilder) {
        super(doWhileStatementBuilder);
        this.body = doWhileStatementBuilder.getBody();
        this.condition = doWhileStatementBuilder.getCondition();
    }

    public Statement getBody() {
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
    public DoWhileStatementBuilder builder() {
        return new DoWhileStatementBuilder(this);
    }

}

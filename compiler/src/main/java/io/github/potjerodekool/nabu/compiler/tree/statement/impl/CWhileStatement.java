package io.github.potjerodekool.nabu.compiler.tree.statement.impl;

import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.Statement;
import io.github.potjerodekool.nabu.compiler.tree.statement.WhileStatement;
import io.github.potjerodekool.nabu.compiler.tree.statement.WhileStatementBuilder;

public class CWhileStatement extends CStatement implements WhileStatement {

    final ExpressionTree condition;
    final Statement body;

    public CWhileStatement(final ExpressionTree condition,
                           final Statement body,
                           final int lineNumber,
                           final int charPositionInLine) {
        super(lineNumber, charPositionInLine);
        this.condition = condition;
        this.body = body;
    }

    public CWhileStatement(final WhileStatementBuilder whileStatementBuilder) {
        super(whileStatementBuilder);
        this.condition = whileStatementBuilder.getCondition();
        this.body = whileStatementBuilder.getBody();
    }

    public ExpressionTree getCondition() {
        return condition;
    }

    public Statement getBody() {
        return body;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitWhileStatement(this, param);
    }

    @Override
    public WhileStatementBuilder builder() {
        return new WhileStatementBuilder(this);
    }

}

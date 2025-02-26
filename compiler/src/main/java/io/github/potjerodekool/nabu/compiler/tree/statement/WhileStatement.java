package io.github.potjerodekool.nabu.compiler.tree.statement;

import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;

public class WhileStatement extends Statement {

    final ExpressionTree condition;
    final Statement body;

    public WhileStatement(final ExpressionTree condition,
                          final Statement body) {
        this.condition = condition;
        this.body = body;
    }

    public WhileStatement(final WhileStatementBuilder whileStatementBuilder) {
        super(whileStatementBuilder);
        this.condition = whileStatementBuilder.condition;
        this.body = whileStatementBuilder.body;
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

    public WhileStatementBuilder builder() {
        return new WhileStatementBuilder(this);
    }

}

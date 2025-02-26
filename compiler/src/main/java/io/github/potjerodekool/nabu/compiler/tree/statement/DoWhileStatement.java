package io.github.potjerodekool.nabu.compiler.tree.statement;

import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;

public class DoWhileStatement extends Statement {

    final Statement body;
    final ExpressionTree condition;

    public DoWhileStatement(final Statement body,
                            final ExpressionTree condition) {
        this.body = body;
        this.condition = condition;
    }

    public DoWhileStatement(final DoWhileStatementBuilder doWhileStatementBuilder) {
        super(doWhileStatementBuilder);
        this.body = doWhileStatementBuilder.body;
        this.condition = doWhileStatementBuilder.condition;
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

    public DoWhileStatementBuilder builder() {
        return new DoWhileStatementBuilder(this);
    }

}

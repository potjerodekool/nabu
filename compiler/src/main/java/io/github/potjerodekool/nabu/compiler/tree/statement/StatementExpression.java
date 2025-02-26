package io.github.potjerodekool.nabu.compiler.tree.statement;

import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;

public class StatementExpression extends Statement {

    final ExpressionTree expression;

    public StatementExpression(final ExpressionTree expression) {
        this.expression = expression;
    }

    protected StatementExpression(final StatementExpressionBuilder builder) {
        super(builder);
        this.expression = builder.expression;
    }

    public ExpressionTree getExpression() {
        return expression;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitStatementExpression(this, param);
    }

    public StatementExpressionBuilder builder() {
        return new StatementExpressionBuilder(this);
    }

}

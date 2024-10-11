package io.github.potjerodekool.nabu.compiler.tree.statement;

import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.expression.CExpression;

import java.util.Objects;

public class StatementExpression extends Statement {

    private final CExpression expression;

    public StatementExpression(final CExpression expression) {
        Objects.requireNonNull(expression);
        this.expression = expression;
    }

    public CExpression getExpression() {
        return expression;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitStatementExpression(this, param);
    }

    public StatementExpressionBuilder builder() {
        return new StatementExpressionBuilder(this);
    }

    public static class StatementExpressionBuilder extends StatementBuilder<StatementExpression> {

        private CExpression expression;

        public StatementExpressionBuilder(final StatementExpression statementExpression) {
            super(statementExpression);
            this.expression = statementExpression.expression;
        }

        public StatementExpressionBuilder expression(final CExpression expression) {
            this.expression = expression;
            return this;
        }

        public StatementExpression build() {
            return fill(new StatementExpression(expression));
        }

    }
}

package io.github.potjerodekool.nabu.compiler.tree.expression;

import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;

public class UnaryExpression extends CExpression {

    private final Operator operator;
    private final CExpression expression;

    public UnaryExpression(final Operator operator,
                           final CExpression expression) {
        this.operator = operator;
        this.expression = expression;
    }

    public Operator getOperator() {
        return operator;
    }

    public CExpression getExpression() {
        return expression;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitUnaryExpression(this, param);
    }

    public UnaryExpressionBuilder builder() {
        return new UnaryExpressionBuilder(this);
    }

    public static class UnaryExpressionBuilder extends CExpressionBuilder<UnaryExpression>{

        private CExpression expression;
        private final Operator operator;

        protected UnaryExpressionBuilder(final UnaryExpression original) {
            super(original);
            expression = original.getExpression();
            operator = original.getOperator();
        }

        public UnaryExpressionBuilder expression(final CExpression expression) {
            this.expression = expression;
            return this;
        }

        @Override
        public UnaryExpression build() {
            return fill(new UnaryExpression(
                    operator,
                    expression
            ));
        }
    }
}

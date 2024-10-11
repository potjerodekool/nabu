package io.github.potjerodekool.nabu.compiler.tree.statement;

import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.expression.CExpression;

public class ReturnStatement extends Statement {

    private CExpression expression;

    public CExpression getExpression() {
        return expression;
    }

    public ReturnStatement expression(final CExpression expression) {
        this.expression = expression;
        return this;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitReturnStatement(this, param);
    }

    public ReturnStatementBuilder builder() {
        return new ReturnStatementBuilder(this);
    }

    public static class ReturnStatementBuilder extends StatementBuilder<ReturnStatement> {

        private CExpression expression;

        protected ReturnStatementBuilder(final ReturnStatement original) {
            super(original);
            this.expression = original.getExpression();
        }

        public ReturnStatementBuilder expression(final CExpression expression) {
            this.expression = expression;
            return this;
        }

        @Override
        public ReturnStatement build() {
            return fill(new ReturnStatement()
                    .expression(expression));
        }
    }

}

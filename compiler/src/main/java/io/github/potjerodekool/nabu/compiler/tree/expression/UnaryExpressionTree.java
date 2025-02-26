package io.github.potjerodekool.nabu.compiler.tree.expression;

import io.github.potjerodekool.nabu.compiler.tree.Tag;
import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;

public class UnaryExpressionTree extends ExpressionTree {

    private Tag tag;
    private final ExpressionTree expression;

    public UnaryExpressionTree(final Tag tag,
                               final ExpressionTree expression) {
        this.tag = tag;
        this.expression = expression;
    }

    protected UnaryExpressionTree(final UnaryExpressionBuilder builder) {
        super(builder);
        this.expression = builder.expression;
    }

    public Tag getTag() {
        return tag;
    }

    public ExpressionTree getExpression() {
        return expression;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitUnaryExpression(this, param);
    }

    public UnaryExpressionBuilder builder() {
        return new UnaryExpressionBuilder(this);
    }

    public static class UnaryExpressionBuilder extends CExpressionBuilder<UnaryExpressionTree>{

        private ExpressionTree expression;

        protected UnaryExpressionBuilder(final UnaryExpressionTree original) {
            super(original);
            expression = original.getExpression();
        }

        @Override
        public CExpressionBuilder<UnaryExpressionTree> self() {
            return this;
        }

        public UnaryExpressionBuilder expression(final ExpressionTree expression) {
            this.expression = expression;
            return this;
        }

        @Override
        public UnaryExpressionTree build() {
            return new UnaryExpressionTree(this);
        }
    }
}

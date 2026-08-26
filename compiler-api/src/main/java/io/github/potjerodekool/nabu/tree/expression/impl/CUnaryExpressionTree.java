package io.github.potjerodekool.nabu.tree.expression.impl;

import io.github.potjerodekool.nabu.tree.Tag;
import io.github.potjerodekool.nabu.tree.TreeVisitor;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.UnaryExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.builder.ExpressionBuilder;

/**
 * Implementation of UnaryExpressionTree.
 */
public class CUnaryExpressionTree extends CExpressionTree implements UnaryExpressionTree {

    private Tag tag;
    private final ExpressionTree expression;

    public CUnaryExpressionTree(final Tag tag,
                                final ExpressionTree expression,
                                final int lineNumber,
                                final int columnNumber) {
        super(lineNumber, columnNumber);
        this.tag = tag;
        this.expression = expression;
    }

    protected CUnaryExpressionTree(final UnaryExpressionBuilder builder) {
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

    public static class UnaryExpressionBuilder extends ExpressionBuilder<UnaryExpressionBuilder> {

        private ExpressionTree expression;

        protected UnaryExpressionBuilder(final CUnaryExpressionTree original) {
            super(original);
            expression = original.getExpression();
        }

        @Override
        public UnaryExpressionBuilder self() {
            return this;
        }

        public UnaryExpressionBuilder expression(final ExpressionTree expression) {
            this.expression = expression;
            return this;
        }

        @Override
        public CUnaryExpressionTree build() {
            return new CUnaryExpressionTree(this);
        }
    }
}

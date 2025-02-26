package io.github.potjerodekool.nabu.compiler.tree.expression;

import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;

public class CastExpressionTree extends ExpressionTree {

    private ExpressionTree expression;

    private ExpressionTree targetType;

    public CastExpressionTree() {
    }

    public CastExpressionTree(final CastExpressionTreeBuilder castExpressionTreeBuilder) {
        super(castExpressionTreeBuilder);
        this.expression = castExpressionTreeBuilder.expression;
        this.targetType = castExpressionTreeBuilder.targetType;
    }

    public ExpressionTree getExpression() {
        return expression;
    }

    public CastExpressionTree expression(final ExpressionTree expression) {
        this.expression = expression;
        return this;
    }

    public ExpressionTree getTargetType() {
        return targetType;
    }

    public CastExpressionTree targetType(final ExpressionTree targetType) {
        this.targetType = targetType;
        return this;
    }

    public CastExpressionTreeBuilder builder() {
        return new CastExpressionTreeBuilder(this);
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitCastExpression(this, param);
    }

    public static class CastExpressionTreeBuilder extends CExpressionBuilder<CastExpressionTree> {

        private ExpressionTree expression;
        private ExpressionTree targetType;

        public CastExpressionTreeBuilder(final CastExpressionTree castExpressionTree) {
            super(castExpressionTree);
            this.expression = castExpressionTree.getExpression();
            this.targetType = castExpressionTree.getTargetType();
        }

        @Override
        public CExpressionBuilder<CastExpressionTree> self() {
            return this;
        }

        public CastExpressionTreeBuilder expression(final ExpressionTree expressionTree) {
            this.expression = expressionTree;
            return this;
        }

        public CastExpressionTreeBuilder targetType(final ExpressionTree targetType) {
            this.targetType = targetType;
            return this;
        }

        @Override
        public CastExpressionTree build() {
            return new CastExpressionTree(this);
        }
    }
}

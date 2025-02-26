package io.github.potjerodekool.nabu.compiler.tree.expression;

import io.github.potjerodekool.nabu.compiler.tree.Tag;
import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;

public class BinaryExpressionTree extends ExpressionTree {

    private final ExpressionTree left;
    private final Tag tag;
    private final ExpressionTree right;

    public BinaryExpressionTree(final ExpressionTree left,
                                final Tag tag,
                                final ExpressionTree right) {
        this.left = left;
        this.tag = tag;
        this.right = right;
    }

    protected BinaryExpressionTree(final BinaryExpressionBuilder builder) {
        super(builder);
        this.left = builder.left;
        this.tag = builder.tag;
        this.right = builder.right;
    }

    public ExpressionTree getLeft() {
        return left;
    }

    public ExpressionTree getRight() {
        return right;
    }

    public Tag getTag() {
        return tag;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R,P> visitor, final P param) {
        return visitor.visitBinaryExpression(this, param);
    }

    @Override
    public String toString() {
        final var leftStr = left.toString();
        final var rightStr = right.toString();
        return String.format("%s %s %s", leftStr, tag, rightStr);
    }

    public BinaryExpressionBuilder builder() {
        return new BinaryExpressionBuilder(this);
    }

    public static class BinaryExpressionBuilder extends CExpressionBuilder<BinaryExpressionTree> {

        private ExpressionTree left;
        private Tag tag;
        private ExpressionTree right;

        public BinaryExpressionBuilder(final BinaryExpressionTree binaryExpression) {
            super(binaryExpression);
            this.left = binaryExpression.left;
            this.right = binaryExpression.right;
            this.tag = binaryExpression.tag;
        }

        @Override
        public CExpressionBuilder<BinaryExpressionTree> self() {
            return this;
        }

        public BinaryExpressionBuilder left(final ExpressionTree left) {
            this.left = left;
            return this;
        }

        public BinaryExpressionBuilder right(final ExpressionTree right) {
            this.right = right;
            return this;
        }

        public BinaryExpressionBuilder tag(final Tag tag) {
            this.tag = tag;
            return this;
        }

        @Override
        public BinaryExpressionTree build() {
            return new BinaryExpressionTree(
                    this
            );
        }
    }
}

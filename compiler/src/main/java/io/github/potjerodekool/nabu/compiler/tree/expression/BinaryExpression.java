package io.github.potjerodekool.nabu.compiler.tree.expression;

import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;

import java.util.Objects;

public class BinaryExpression extends CExpression {

    private final CExpression left;
    private final Operator operator;
    private final CExpression right;

    public BinaryExpression(final CExpression left,
                            final Operator operator,
                            final CExpression right) {
        Objects.requireNonNull(left);
        Objects.requireNonNull(operator);
        Objects.requireNonNull(right);
        this.left = left;
        this.operator = operator;
        this.right = right;
    }

    public CExpression getLeft() {
        return left;
    }

    public CExpression getRight() {
        return right;
    }

    public Operator getOperator() {
        return operator;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R,P> visitor, final P param) {
        return visitor.visitBinaryExpression(this, param);
    }

    @Override
    public String toString() {
        final var leftStr = left.toString();
        final var rightStr = right.toString();
        return String.format("%s %s %s", leftStr, operator, rightStr);
    }

    public BinaryExpressionBuilder builder() {
        return new BinaryExpressionBuilder(this);
    }

    public static class BinaryExpressionBuilder extends CExpressionBuilder<BinaryExpression> {

        private CExpression left;
        private Operator operator;
        private CExpression right;

        public BinaryExpressionBuilder(final BinaryExpression binaryExpression) {
            super(binaryExpression);
            this.left = binaryExpression.left;
            this.right = binaryExpression.right;
            this.operator = binaryExpression.operator;
        }

        public BinaryExpressionBuilder left(final CExpression left) {
            this.left = left;
            return this;
        }

        public BinaryExpressionBuilder right(final CExpression right) {
            this.right = right;
            return this;
        }

        public BinaryExpressionBuilder operator(final Operator operator) {
            this.operator = operator;
            return this;
        }

        @Override
        public BinaryExpression build() {
            final var newExpression = new BinaryExpression(
                    left,
                    operator,
                    right
            );
            fill(newExpression);
            return newExpression;
        }
    }
}

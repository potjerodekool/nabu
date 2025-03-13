package io.github.potjerodekool.nabu.compiler.tree.expression.builder;

import io.github.potjerodekool.nabu.compiler.tree.Tag;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.impl.CBinaryExpressionTree;

public class BinaryExpressionBuilder extends ExpressionBuilder<CBinaryExpressionTree> {

    private ExpressionTree left;
    private Tag tag;
    private ExpressionTree right;

    public BinaryExpressionBuilder(final CBinaryExpressionTree binaryExpression) {
        super(binaryExpression);
        this.left = binaryExpression.getLeft();
        this.right = binaryExpression.getRight();
        this.tag = binaryExpression.getTag();
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
    public ExpressionBuilder<CBinaryExpressionTree> self() {
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
    public CBinaryExpressionTree build() {
        return new CBinaryExpressionTree(this);
    }
}

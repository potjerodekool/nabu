package io.github.potjerodekool.nabu.tree.expression.impl;
import io.github.potjerodekool.nabu.tree.Tree;
import io.github.potjerodekool.nabu.tree.TreeVisitor;
import io.github.potjerodekool.nabu.tree.expression.BinaryExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.builder.BinaryExpressionBuilder;
import io.github.potjerodekool.nabu.tree.Tag;
import io.github.potjerodekool.nabu.util.CollectionUtils;

import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/**
 * Implementation of BinaryExpressionTree.
 */
public class CBinaryExpressionTree extends CExpressionTree implements BinaryExpressionTree {

    private final ExpressionTree left;
    private final Tag tag;
    private final ExpressionTree right;

    public CBinaryExpressionTree(final ExpressionTree left,
                                 final Tag tag,
                                 final ExpressionTree right) {
        this(left, tag, right, -1, -1);
    }

    public CBinaryExpressionTree(final ExpressionTree left,
                                 final Tag tag,
                                 final ExpressionTree right,
                                 final int lineNumber,
                                 final int columnNumber) {
        super(lineNumber, columnNumber);
        Objects.requireNonNull(left);
        Objects.requireNonNull(right);

        this.left = left;
        this.tag = tag;
        this.right = right;
    }

    public CBinaryExpressionTree(final BinaryExpressionBuilder builder) {
        super(builder);
        this.left = builder.getLeft();
        this.tag = builder.getTag();
        this.right = builder.getRight();
        Objects.requireNonNull(left);
        Objects.requireNonNull(right);

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

    @Override
    public BinaryExpressionBuilder builder() {
        return new BinaryExpressionBuilder(this);
    }

    @Override
    public List<? extends Tree> children() {
        return List.of(left, right);
    }
}

package io.github.potjerodekool.nabu.tree.expression.impl;

import io.github.potjerodekool.nabu.tree.TreeVisitor;
import io.github.potjerodekool.nabu.tree.expression.AssignmentExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;

public class CAssignmentExpressionTree extends CExpressionTree implements AssignmentExpressionTree {

    private final ExpressionTree left;
    private final ExpressionTree right;

    public CAssignmentExpressionTree(final ExpressionTree left,
                                     final ExpressionTree right) {
        this(left, right, -1, -1);
    }

    public CAssignmentExpressionTree(final ExpressionTree left,
                                     final ExpressionTree right,
                                     final int lineNumber,
                                     final int columnNumber) {
        super(lineNumber, columnNumber);
        this.left = left;
        this.right = right;
    }

    public ExpressionTree getLeft() {
        return left;
    }

    public ExpressionTree getRight() {
        return right;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitAssignment(this, param);
    }
}

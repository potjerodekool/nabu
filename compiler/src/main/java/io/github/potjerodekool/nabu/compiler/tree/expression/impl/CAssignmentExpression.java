package io.github.potjerodekool.nabu.compiler.tree.expression.impl;

import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.expression.AssignmentExpression;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;

public class CAssignmentExpression extends CExpressionTree implements AssignmentExpression {

    private final ExpressionTree left;
    private final ExpressionTree right;

    public CAssignmentExpression(final ExpressionTree left,
                                 final ExpressionTree right,
                                 final int lineNumber,
                                 final int charPositionInLine) {
        super(lineNumber, charPositionInLine);
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

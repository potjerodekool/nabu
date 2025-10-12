package io.github.potjerodekool.nabu.tree.expression;

public interface AssignmentExpressionTree extends ExpressionTree  {

    ExpressionTree getLeft();

    ExpressionTree getRight();
}

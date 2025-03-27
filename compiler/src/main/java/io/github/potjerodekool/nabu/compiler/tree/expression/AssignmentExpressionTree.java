package io.github.potjerodekool.nabu.compiler.tree.expression;

public interface AssignmentExpressionTree extends ExpressionTree  {

    ExpressionTree getLeft();

    ExpressionTree getRight();
}

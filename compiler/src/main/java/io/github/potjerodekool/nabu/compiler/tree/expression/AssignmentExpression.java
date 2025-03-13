package io.github.potjerodekool.nabu.compiler.tree.expression;

public interface AssignmentExpression extends ExpressionTree  {

    ExpressionTree getLeft();

    ExpressionTree getRight();
}

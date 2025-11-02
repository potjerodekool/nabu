package io.github.potjerodekool.nabu.tree.expression;

/**
 * Assignment expression used to assign value in an Annotation.
 * For example:
 * {@code @}Deprecated(since = "4.2")
 */
public interface AssignmentExpressionTree extends ExpressionTree  {

    ExpressionTree getLeft();

    ExpressionTree getRight();
}

package io.github.potjerodekool.nabu.tree.expression;

/**
 * Type name expression.
 */
public interface TypeNameExpressionTree extends ExpressionTree {

    ExpressionTree getPackageName();

    ExpressionTree getIdentifier();

}

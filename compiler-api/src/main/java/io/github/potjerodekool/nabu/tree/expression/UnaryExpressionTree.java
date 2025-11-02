package io.github.potjerodekool.nabu.tree.expression;

import io.github.potjerodekool.nabu.tree.Tag;

/**
 * Unary expression.
 * For example: i++
 */
public interface UnaryExpressionTree extends ExpressionTree {

    Tag getTag();

    ExpressionTree getExpression();

}

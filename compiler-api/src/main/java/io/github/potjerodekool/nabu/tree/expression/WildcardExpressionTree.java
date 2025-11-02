package io.github.potjerodekool.nabu.tree.expression;

import io.github.potjerodekool.nabu.type.BoundKind;

/**
 * Wildcard expression
 */
public interface WildcardExpressionTree extends ExpressionTree {

    ExpressionTree getBound();

    BoundKind getBoundKind();

}

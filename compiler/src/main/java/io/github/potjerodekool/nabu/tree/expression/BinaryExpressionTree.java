package io.github.potjerodekool.nabu.tree.expression;

import io.github.potjerodekool.nabu.tree.Tag;
import io.github.potjerodekool.nabu.tree.expression.builder.BinaryExpressionBuilder;

/**
 * Binary expression.
 * For example:
 * 1 + 2
 */
public interface BinaryExpressionTree extends ExpressionTree {

    /**
     * @return Returns the left expression.
     */
    ExpressionTree getLeft();

    /**
     * @return Returns the right expression.
     */
    ExpressionTree getRight();

    /**
     * @return Returns the operator tag.
     */
    Tag getTag();

    BinaryExpressionBuilder builder();
}

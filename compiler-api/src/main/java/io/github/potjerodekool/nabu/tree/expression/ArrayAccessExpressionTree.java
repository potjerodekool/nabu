package io.github.potjerodekool.nabu.tree.expression;

import io.github.potjerodekool.nabu.tree.expression.builder.ArrayAccessExpressionBuilder;

/**
 * Array access expression.
 * For example:
 * items[0]
 */
public interface ArrayAccessExpressionTree extends ExpressionTree {

    /**
     * @return Returns array expression.
     */
    ExpressionTree getExpression();

    /**
     * @return Returns the index expression.
     */
    ExpressionTree getIndex();

    /**
     * See {@link ExpressionTree#builder}
     */
    @Override
    ArrayAccessExpressionBuilder builder();
}

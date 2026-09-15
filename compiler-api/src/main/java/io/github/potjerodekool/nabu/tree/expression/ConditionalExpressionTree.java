package io.github.potjerodekool.nabu.tree.expression;

import io.github.potjerodekool.nabu.tree.expression.builder.ConditionalExpressionTreeBuilder;

/**
 * Conditional (ternary) expression.
 * For example:
 * a ? b : c
 */
public interface ConditionalExpressionTree extends ExpressionTree {

    /**
     * @return Returns the condition expression.
     */
    ExpressionTree getCondition();

    /**
     * @return Returns the expression evaluated when the condition is true.
     */
    ExpressionTree getTrueExpression();

    /**
     * @return Returns the expression evaluated when the condition is false.
     */
    ExpressionTree getFalseExpression();

    ConditionalExpressionTreeBuilder builder();
}

package io.github.potjerodekool.nabu.tree.expression.builder;

import io.github.potjerodekool.nabu.tree.expression.ConditionalExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.impl.CConditionalExpressionTree;

/**
 * Builder for conditional (ternary) expressions.
 */
public class ConditionalExpressionTreeBuilder
        extends ExpressionBuilder<ConditionalExpressionTreeBuilder> {

    private ExpressionTree condition;
    private ExpressionTree trueExpression;
    private ExpressionTree falseExpression;

    public ConditionalExpressionTreeBuilder() {
    }

    public ConditionalExpressionTreeBuilder(final ConditionalExpressionTree conditionalExpression) {
        super(conditionalExpression);
        this.condition = conditionalExpression.getCondition();
        this.trueExpression = conditionalExpression.getTrueExpression();
        this.falseExpression = conditionalExpression.getFalseExpression();
    }

    public ExpressionTree getCondition() {
        return condition;
    }

    public ExpressionTree getTrueExpression() {
        return trueExpression;
    }

    public ExpressionTree getFalseExpression() {
        return falseExpression;
    }

    @Override
    public ConditionalExpressionTreeBuilder self() {
        return this;
    }

    public ConditionalExpressionTreeBuilder condition(final ExpressionTree condition) {
        this.condition = condition;
        return this;
    }

    public ConditionalExpressionTreeBuilder trueExpression(final ExpressionTree trueExpression) {
        this.trueExpression = trueExpression;
        return this;
    }

    public ConditionalExpressionTreeBuilder falseExpression(final ExpressionTree falseExpression) {
        this.falseExpression = falseExpression;
        return this;
    }

    @Override
    public ConditionalExpressionTree build() {
        return new CConditionalExpressionTree(this);
    }
}

package io.github.potjerodekool.nabu.tree.expression.builder;


import io.github.potjerodekool.nabu.tree.expression.CastExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.impl.CCastExpressionTree;

/**
 * Builder for cast expressions.
 */
public class CastExpressionTreeBuilder extends ExpressionBuilder<CastExpressionTreeBuilder> {

    private ExpressionTree expression;
    private ExpressionTree targetType;

    public CastExpressionTreeBuilder(final CastExpressionTree castExpressionTree) {
        super(castExpressionTree);
        this.expression = castExpressionTree.getExpression();
        this.targetType = castExpressionTree.getTargetType();
    }

    public ExpressionTree getExpression() {
        return expression;
    }

    public ExpressionTree getTargetType() {
        return targetType;
    }

    @Override
    public CastExpressionTreeBuilder self() {
        return this;
    }

    public CastExpressionTreeBuilder expression(final ExpressionTree expressionTree) {
        this.expression = expressionTree;
        return this;
    }

    public CastExpressionTreeBuilder targetType(final ExpressionTree targetType) {
        this.targetType = targetType;
        return this;
    }

    @Override
    public CastExpressionTree build() {
        return new CCastExpressionTree(this);
    }
}

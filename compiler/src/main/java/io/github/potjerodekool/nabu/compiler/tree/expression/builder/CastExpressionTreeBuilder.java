package io.github.potjerodekool.nabu.compiler.tree.expression.builder;

import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.impl.CCastExpressionTree;

public class CastExpressionTreeBuilder extends ExpressionBuilder<CCastExpressionTree> {

    private ExpressionTree expression;
    private ExpressionTree targetType;

    public CastExpressionTreeBuilder(final CCastExpressionTree castExpressionTree) {
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
    public CCastExpressionTree build() {
        return new CCastExpressionTree(this);
    }
}

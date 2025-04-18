package io.github.potjerodekool.nabu.compiler.tree.expression.impl;

import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.expression.CastExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.builder.CastExpressionTreeBuilder;

public class CCastExpressionTree extends CExpressionTree implements CastExpressionTree {

    private ExpressionTree expression;

    private ExpressionTree targetType;

    public CCastExpressionTree(final ExpressionTree targetType,
                               final ExpressionTree expression) {
        this(targetType, expression, -1, -1);
    }

    public CCastExpressionTree(final ExpressionTree targetType,
                               final ExpressionTree expression,
                               final int lineNumber,
                               final int columnNumber) {
        super(lineNumber, columnNumber);
        this.targetType = targetType;
        this.expression = expression;
    }

    public CCastExpressionTree(final CastExpressionTreeBuilder castExpressionTreeBuilder) {
        super(castExpressionTreeBuilder);
        this.expression = castExpressionTreeBuilder.getExpression();
        this.targetType = castExpressionTreeBuilder.getTargetType();
    }

    public ExpressionTree getExpression() {
        return expression;
    }

    public CCastExpressionTree expression(final ExpressionTree expression) {
        this.expression = expression;
        return this;
    }

    public ExpressionTree getTargetType() {
        return targetType;
    }

    public CCastExpressionTree targetType(final ExpressionTree targetType) {
        this.targetType = targetType;
        return this;
    }

    public CastExpressionTreeBuilder builder() {
        return new CastExpressionTreeBuilder(this);
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitCastExpression(this, param);
    }
}

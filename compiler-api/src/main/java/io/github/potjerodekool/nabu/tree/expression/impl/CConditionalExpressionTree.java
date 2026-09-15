package io.github.potjerodekool.nabu.tree.expression.impl;

import io.github.potjerodekool.nabu.tree.Tree;
import io.github.potjerodekool.nabu.tree.TreeVisitor;
import io.github.potjerodekool.nabu.tree.expression.ConditionalExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.builder.ConditionalExpressionTreeBuilder;

import java.util.List;
import java.util.Objects;

/**
 * Implementation of ConditionalExpressionTree.
 */
public class CConditionalExpressionTree extends CExpressionTree
        implements ConditionalExpressionTree {

    private final ExpressionTree condition;
    private final ExpressionTree trueExpression;
    private final ExpressionTree falseExpression;

    public CConditionalExpressionTree(final ExpressionTree condition,
                                      final ExpressionTree trueExpression,
                                      final ExpressionTree falseExpression,
                                      final int lineNumber,
                                      final int columnNumber) {
        super(lineNumber, columnNumber);
        Objects.requireNonNull(condition);
        Objects.requireNonNull(trueExpression);
        Objects.requireNonNull(falseExpression);

        this.condition = condition;
        this.trueExpression = trueExpression;
        this.falseExpression = falseExpression;
    }

    public CConditionalExpressionTree(final ConditionalExpressionTreeBuilder builder) {
        super(builder);
        this.condition = builder.getCondition();
        this.trueExpression = builder.getTrueExpression();
        this.falseExpression = builder.getFalseExpression();
        Objects.requireNonNull(condition);
        Objects.requireNonNull(trueExpression);
        Objects.requireNonNull(falseExpression);
    }

    @Override
    public ExpressionTree getCondition() {
        return condition;
    }

    @Override
    public ExpressionTree getTrueExpression() {
        return trueExpression;
    }

    @Override
    public ExpressionTree getFalseExpression() {
        return falseExpression;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitConditionalExpression(this, param);
    }

    @Override
    public String toString() {
        return condition + " ? " + trueExpression + " : " + falseExpression;
    }

    @Override
    public ConditionalExpressionTreeBuilder builder() {
        return new ConditionalExpressionTreeBuilder(this);
    }

    @Override
    public List<? extends Tree> children() {
        return List.of(condition, trueExpression, falseExpression);
    }
}

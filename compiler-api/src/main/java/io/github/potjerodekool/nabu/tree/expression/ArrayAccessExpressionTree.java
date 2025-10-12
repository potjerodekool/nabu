package io.github.potjerodekool.nabu.tree.expression;

import io.github.potjerodekool.nabu.tree.expression.builder.ArrayAccessExpressionBuilder;

public interface ArrayAccessExpressionTree extends ExpressionTree {
    ExpressionTree getExpression();

    ExpressionTree getIndex();

    ArrayAccessExpressionBuilder builder();
}

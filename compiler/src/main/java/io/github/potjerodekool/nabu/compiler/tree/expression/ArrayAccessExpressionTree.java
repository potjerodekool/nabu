package io.github.potjerodekool.nabu.compiler.tree.expression;

import io.github.potjerodekool.nabu.compiler.tree.expression.builder.ArrayAccessExpressionBuilder;

public interface ArrayAccessExpressionTree extends ExpressionTree {
    ExpressionTree getExpression();

    ExpressionTree getIndex();

    ArrayAccessExpressionBuilder builder();
}

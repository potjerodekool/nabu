package io.github.potjerodekool.nabu.compiler.tree.expression;

import io.github.potjerodekool.nabu.compiler.tree.expression.builder.FieldAccessExpressionBuilder;

public interface FieldAccessExpressionTree extends ExpressionTree {

    ExpressionTree getTarget();

    ExpressionTree getField();

    FieldAccessExpressionTree target(ExpressionTree newTarget);

    FieldAccessExpressionBuilder builder();
}

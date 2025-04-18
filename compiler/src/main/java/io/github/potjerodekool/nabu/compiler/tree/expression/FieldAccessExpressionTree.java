package io.github.potjerodekool.nabu.compiler.tree.expression;

import io.github.potjerodekool.nabu.compiler.tree.expression.builder.FieldAccessExpressionBuilder;

public interface FieldAccessExpressionTree extends ExpressionTree {

    ExpressionTree getSelected();

    ExpressionTree getField();

    FieldAccessExpressionTree selected(ExpressionTree selected);

    FieldAccessExpressionBuilder builder();
}

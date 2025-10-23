package io.github.potjerodekool.nabu.tree.expression;

import io.github.potjerodekool.nabu.tree.expression.builder.FieldAccessExpressionBuilder;

public interface FieldAccessExpressionTree extends ExpressionTree {

    ExpressionTree getSelected();

    IdentifierTree getField();

    FieldAccessExpressionTree selected(ExpressionTree selected);

    FieldAccessExpressionBuilder builder();
}

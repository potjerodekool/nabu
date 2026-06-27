package io.github.potjerodekool.nabu.tree.expression;

import io.github.potjerodekool.nabu.tree.expression.builder.FieldAccessExpressionBuilder;

/**
 * Field access expression.
 * For example:
 * this.firstName
 * <p> </p>
 * Or as selector in method invocation:
 * this.firstName.length()
 */
public interface FieldAccessExpressionTree extends ExpressionTree {

    ExpressionTree getSelected();

    IdentifierTree getField();

    FieldAccessExpressionTree selected(ExpressionTree selected);

    FieldAccessExpressionBuilder builder();
}

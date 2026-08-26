package io.github.potjerodekool.nabu.tree.expression;

import io.github.potjerodekool.nabu.tree.expression.builder.FieldAccessExpressionBuilder;
import io.github.potjerodekool.nabu.tree.expression.impl.CFieldAccessExpressionTree;

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

    static FieldAccessExpressionTree create(final ExpressionTree selected,
                                            final IdentifierTree field) {
        return new CFieldAccessExpressionTree(selected, field);
    }

    static FieldAccessExpressionTree create(final ExpressionTree selected,
                                            final IdentifierTree field,
                                            final int lineNumber,
                                            final int columnNumber) {
        return new CFieldAccessExpressionTree(selected, field, lineNumber, columnNumber);
    }
}

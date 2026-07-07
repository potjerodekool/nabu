package io.github.potjerodekool.nabu.tree.expression;

import io.github.potjerodekool.nabu.tree.expression.impl.CNewArrayExpression;

import java.util.List;

/**
 * New array expression.
 * For example:
 * new String[10]
 * new String[]{"A", "B", "C"}
 */
public interface NewArrayExpression extends ExpressionTree {

    ExpressionTree getElementType();

    List<ExpressionTree> getDimensions();

    List<? extends ExpressionTree> getElements();

    static NewArrayExpression create(final ExpressionTree elementType,
                                     final List<ExpressionTree> dimensions,
                                     final List<ExpressionTree> elements,
                                     final int lineNumber,
                                     final int columnNumber) {
        return new CNewArrayExpression(elementType, dimensions, elements, lineNumber, columnNumber);
    }

}

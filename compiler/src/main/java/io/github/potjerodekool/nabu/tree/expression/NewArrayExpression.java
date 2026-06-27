package io.github.potjerodekool.nabu.tree.expression;

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

}

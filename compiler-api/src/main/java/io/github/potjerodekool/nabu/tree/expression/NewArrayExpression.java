package io.github.potjerodekool.nabu.tree.expression;

import java.util.List;

public interface NewArrayExpression extends ExpressionTree {

    ExpressionTree getElementType();

    List<ExpressionTree> getDimensions();

    List<? extends ExpressionTree> getElements();

}

package io.github.potjerodekool.nabu.tree.expression.impl;


import io.github.potjerodekool.nabu.tree.TreeVisitor;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.NewArrayExpression;

import java.util.List;

public class CNewArrayExpression extends CExpressionTree implements NewArrayExpression {

    private final ExpressionTree elementType;
    private final List<ExpressionTree> dimensions;
    private final List<ExpressionTree> elements;

    public CNewArrayExpression(final ExpressionTree elementType,
                               final List<ExpressionTree> dimensions,
                               final List<ExpressionTree> elements) {
        this(elementType, dimensions, elements, -1, -1);
    }

    public CNewArrayExpression(final ExpressionTree elementType,
                               final List<ExpressionTree> dimensions,
                               final List<ExpressionTree> elements,
                               final int lineNumber,
                               final int columnNumber) {
        super(lineNumber, columnNumber);
        this.elementType = elementType;
        this.dimensions = dimensions;
        this.elements = elements;
    }

    @Override
    public ExpressionTree getElementType() {
        return elementType;
    }

    @Override
    public List<ExpressionTree> getDimensions() {
        return dimensions;
    }

    public List<ExpressionTree> getElements() {
        return elements;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitNewArray(this, param);
    }
}

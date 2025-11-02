package io.github.potjerodekool.nabu.tree.impl;

import io.github.potjerodekool.nabu.lang.model.element.Element;
import io.github.potjerodekool.nabu.tree.ErrorTree;
import io.github.potjerodekool.nabu.tree.TreeVisitor;
import io.github.potjerodekool.nabu.tree.expression.builder.ExpressionBuilder;
import io.github.potjerodekool.nabu.type.TypeMirror;

/**
 * Implementation of ErrorTree.
 */
public class CErrorTree extends CTree implements ErrorTree {

    public CErrorTree(final int lineNumber,
                      final int columnNumber) {
        super(lineNumber, columnNumber);
    }
    @Override

    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitErroneous(this, param);
    }

    @Override
    public Element getSymbol() {
        return null;
    }

    @Override
    public void setSymbol(final Element symbol) {

    }

    @Override
    public TypeMirror getType() {
        return null;
    }

    @Override
    public void setType(final TypeMirror type) {
    }

    @Override
    public ExpressionBuilder<?> builder() {
        return new ExpressionBuilder(this);
    }
}

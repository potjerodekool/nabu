package io.github.potjerodekool.nabu.tree.expression.impl;

import io.github.potjerodekool.nabu.lang.model.element.Element;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.builder.ExpressionBuilder;
import io.github.potjerodekool.nabu.tree.impl.CTree;
import io.github.potjerodekool.nabu.type.TypeMirror;

public abstract class CExpressionTree extends CTree implements ExpressionTree {

    private Element symbol;

    private TypeMirror type;

    public CExpressionTree(final int lineNumber,
                           final int columnNumber) {
        super(lineNumber, columnNumber);
    }

    public <E extends ExpressionTree,
            EB extends ExpressionBuilder<E, EB>> CExpressionTree(final ExpressionBuilder<E, EB> builder) {
        super(builder);
        this.symbol = builder.getSymbol();
        this.type = builder.getType();
    }

    public Element getSymbol() {
        return symbol;
    }

    @Override
    public void setSymbol(final Element symbol) {
        this.symbol = symbol;
    }

    public TypeMirror getType() {
        return type;
    }

    @Override
    public void setType(final TypeMirror type) {
        this.type = type;
    }

}

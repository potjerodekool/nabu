package io.github.potjerodekool.nabu.tree.expression.impl;

import io.github.potjerodekool.nabu.lang.model.element.Element;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.builder.ExpressionBuilder;
import io.github.potjerodekool.nabu.tree.impl.CTree;

/**
 * Base class for expression trees.
 */
public abstract class CExpressionTree extends CTree implements ExpressionTree {

    private Element symbol;

    public CExpressionTree(final int lineNumber,
                           final int columnNumber) {
        super(lineNumber, columnNumber);
    }

    public <EB extends ExpressionBuilder<EB>> CExpressionTree(final ExpressionBuilder<EB> builder) {
        super(builder);
        this.symbol = builder.getSymbol();
        setType(builder.getType());
    }

    public Element getSymbol() {
        return symbol;
    }

    @Override
    public void setSymbol(final Element symbol) {
        this.symbol = symbol;
    }

    @Override
    public ExpressionBuilder<?> builder() {
        return new ExpressionBuilder(this);
    }
}

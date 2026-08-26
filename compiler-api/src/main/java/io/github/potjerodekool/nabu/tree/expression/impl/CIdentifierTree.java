package io.github.potjerodekool.nabu.tree.expression.impl;

import io.github.potjerodekool.nabu.lang.model.element.Element;
import io.github.potjerodekool.nabu.tree.TreeVisitor;
import io.github.potjerodekool.nabu.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.tree.expression.builder.IdentifierTreeBuilder;

/**
 * Implementation of IdentifierTree.
 */
public class CIdentifierTree extends CExpressionTree implements IdentifierTree {

    private final String name;

    public CIdentifierTree(final String name) {
        this(name, -1, -1);
    }

    public CIdentifierTree(final String name,
                           final int line,
                           final int columnNumber) {
        super(line, columnNumber);
        this.name = name;
    }

    public CIdentifierTree(final IdentifierTreeBuilder identifierTreeBuilder) {
        super(identifierTreeBuilder);
        this.name = identifierTreeBuilder.getName();
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitIdentifier(this, param);
    }

    @Override
    public void setSymbol(final Element symbol) {
        super.setSymbol(symbol);
    }

    @Override
    public String toString() {
        return name;
    }

}

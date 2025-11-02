package io.github.potjerodekool.nabu.tree.expression.impl;

import io.github.potjerodekool.nabu.tree.Tree;
import io.github.potjerodekool.nabu.tree.TreeVisitor;
import io.github.potjerodekool.nabu.tree.expression.ArrayTypeTree;
import io.github.potjerodekool.nabu.tree.expression.Dimension;

import java.util.ArrayList;
import java.util.List;

/**
 * Implementation of ArrayTypeTree.
 */
public class CArrayTypeTree extends CExpressionTree implements ArrayTypeTree {

    private final Tree componentType;
    private final List<Dimension> dimensions;

    public CArrayTypeTree(final Tree componentType,
                          final List<Dimension> dimensions) {
        this(componentType, dimensions, -1, -1);
    }

    public CArrayTypeTree(final Tree componentType,
                          final List<Dimension> dimensions,
                          final int lineNumber,
                          final int columnNumber) {
        super(lineNumber, columnNumber);
        this.componentType = componentType;
        this.dimensions = new ArrayList<>(dimensions);
    }

    public Tree getComponentType() {
        return componentType;
    }

    @Override
    public List<Dimension> getDimensions() {
        return dimensions;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitArrayType(this, param);
    }
}

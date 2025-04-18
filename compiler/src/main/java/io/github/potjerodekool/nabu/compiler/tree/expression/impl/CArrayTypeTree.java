package io.github.potjerodekool.nabu.compiler.tree.expression.impl;

import io.github.potjerodekool.nabu.compiler.tree.Tree;
import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.expression.ArrayTypeTree;

public class CArrayTypeTree extends CExpressionTree implements ArrayTypeTree {

    private final Tree componentType;

    public CArrayTypeTree(final Tree componentType) {
        this(componentType, -1, -1);
    }

    public CArrayTypeTree(final Tree componentType,
                          final int lineNumber,
                          final int columnNumber) {
        super(lineNumber, columnNumber);
        this.componentType = componentType;
    }

    public Tree getComponentType() {
        return componentType;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitArrayType(this, param);
    }
}

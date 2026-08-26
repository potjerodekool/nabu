package io.github.potjerodekool.nabu.tree.impl;

import io.github.potjerodekool.nabu.tree.TreeVisitor;
import io.github.potjerodekool.nabu.tree.builder.TreeBuilder;
import io.github.potjerodekool.nabu.type.TypeMirror;

/**
 * Base class for trees.
 */
public abstract class CTree {

    private final int lineNumber;
    private final int columnNumber;
    private TypeMirror type;

    public CTree(final int lineNumber,
                 final int columnNumber) {
        this.lineNumber = lineNumber;
        this.columnNumber = columnNumber;
    }

    public CTree(final TreeBuilder<?> treeBuilder) {
        this.lineNumber = treeBuilder.getLineNumber();
        this.columnNumber = treeBuilder.getColumnNumber();
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public int getColumnNumber() {
        return columnNumber;
    }

    public TypeMirror getType() {
        return type;
    }

    public void setType(final TypeMirror type) {
        this.type = type;
    }

    public abstract <R, P> R accept(TreeVisitor<R, P> visitor, P param);

}

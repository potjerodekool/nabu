package io.github.potjerodekool.nabu.compiler.tree.impl;

import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.builder.TreeBuilder;

public abstract class CTree {

    private final int lineNumber;
    private final int columnNumber;

    public CTree(final int lineNumber,
                 final int columnNumber) {
        this.lineNumber = lineNumber;
        this.columnNumber = columnNumber;
    }

    public CTree(final TreeBuilder<?, ?> treeBuilder) {
        this.lineNumber = treeBuilder.getLineNumber();
        this.columnNumber = treeBuilder.getColumnNumber();
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public int getColumnNumber() {
        return columnNumber;
    }

    public abstract <R, P> R accept(TreeVisitor<R, P> visitor, P param);

}

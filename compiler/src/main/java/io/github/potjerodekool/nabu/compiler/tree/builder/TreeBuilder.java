package io.github.potjerodekool.nabu.compiler.tree.builder;

import io.github.potjerodekool.nabu.compiler.tree.Tree;

public abstract class TreeBuilder<E extends Tree, TB extends TreeBuilder<E, TB>> {

    private int lineNumber;
    private int columnNumber;

    protected TreeBuilder() {
        this.lineNumber = -1;
        this.columnNumber = -1;
    }

    protected TreeBuilder(final Tree original) {
        this.lineNumber = original.getLineNumber();
        this.columnNumber = original.getColumnNumber();
    }

    public abstract TB self();

    public int getLineNumber() {
        return lineNumber;
    }

    public TB lineNumber(final int lineNumber) {
        this.lineNumber = lineNumber;
        return self();
    }

    public int getColumnNumber() {
        return columnNumber;
    }

    public TB columnNumber(final int columnNumber) {
        this.columnNumber = columnNumber;
        return self();
    }

    public abstract E build();

}

package io.github.potjerodekool.nabu.tree.builder;

import io.github.potjerodekool.nabu.tree.Tree;

/**
 * Base class for tree builders.
 * @param <TB> TreeBuilder type. Should be the type that this class implements.
 * This type variable is used as return type of the self method to allow method chaining.
 */
public abstract class TreeBuilder<TB extends TreeBuilder<TB>> {

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

    public abstract Tree build();

}

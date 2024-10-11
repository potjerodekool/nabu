package io.github.potjerodekool.nabu.compiler.tree;

import io.github.potjerodekool.nabu.compiler.tree.expression.BinaryExpression;

public abstract class Tree {

    private int lineNumber = -1;
    private int columnNumber = -1;

    public int getLineNumber() {
        return lineNumber;
    }

    public void setLineNumber(final int lineNumber) {
        this.lineNumber = lineNumber;
    }

    public int getColumnNumber() {
        return columnNumber;
    }

    public void setColumnNumber(final int columnNumber) {
        this.columnNumber = columnNumber;
    }

    public abstract <R, P> R accept(TreeVisitor<R, P> visitor, P param);

    protected static abstract class TreeBuilder<E extends Tree> {

        private final int lineNumber;
        private final int columnNumber;

        protected TreeBuilder(final Tree original) {
            this.lineNumber = original.lineNumber;
            this.columnNumber = original.columnNumber;
        }

        protected E fill(final E tree) {
            tree.setLineNumber(lineNumber);
            tree.setColumnNumber(columnNumber);
            return tree;
        }

        public abstract E build();

    }
}

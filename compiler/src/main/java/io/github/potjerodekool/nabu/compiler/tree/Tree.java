package io.github.potjerodekool.nabu.compiler.tree;

public abstract class Tree {

    private int lineNumber = -1;
    private int columnNumber = -1;

    public Tree() {
    }

    public Tree(final int lineNumber,
                final int columnNumber) {
        this.lineNumber = lineNumber;
        this.columnNumber = columnNumber;
    }

    public Tree(final TreeBuilder<?, ?> treeBuilder) {
        this.lineNumber = treeBuilder.getLineNumber();
        this.columnNumber = treeBuilder.getColumnNumber();
    }

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

    public static abstract class TreeBuilder<E extends Tree, TB extends TreeBuilder<E, TB>> {

        private int lineNumber;
        private int columnNumber;

        protected TreeBuilder() {
            this(null);
        }

        protected TreeBuilder(final Tree original) {
            if (original != null) {
                this.lineNumber = original.getLineNumber();
                this.columnNumber = original.getColumnNumber();
            } else {
                this.lineNumber = -1;
                this.columnNumber = -1;
            }
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
}

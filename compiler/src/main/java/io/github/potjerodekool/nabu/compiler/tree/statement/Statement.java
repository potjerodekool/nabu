package io.github.potjerodekool.nabu.compiler.tree.statement;

import io.github.potjerodekool.nabu.compiler.tree.Tree;

public abstract class Statement extends Tree {

    protected static abstract class StatementBuilder<E extends Statement> extends TreeBuilder<E> {

        protected StatementBuilder(final E original) {
            super(original);
        }
    }

}

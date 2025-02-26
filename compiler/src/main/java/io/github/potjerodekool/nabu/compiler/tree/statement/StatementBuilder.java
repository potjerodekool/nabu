package io.github.potjerodekool.nabu.compiler.tree.statement;

import io.github.potjerodekool.nabu.compiler.tree.Tree;

public abstract class StatementBuilder<E extends Statement> extends Tree.TreeBuilder<E, StatementBuilder<E>> {

    protected StatementBuilder(final E original) {
        super(original);
    }
}

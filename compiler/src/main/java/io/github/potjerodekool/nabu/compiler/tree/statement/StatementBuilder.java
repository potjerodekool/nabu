package io.github.potjerodekool.nabu.compiler.tree.statement;

import io.github.potjerodekool.nabu.compiler.tree.builder.TreeBuilder;

public abstract class StatementBuilder<E extends Statement, SB extends StatementBuilder<E, SB>> extends TreeBuilder<E, SB> {

    protected StatementBuilder(final E original) {
        super(original);
    }

    protected StatementBuilder() {
        super();
    }
}

package io.github.potjerodekool.nabu.tree.statement.builder;

import io.github.potjerodekool.nabu.tree.builder.TreeBuilder;
import io.github.potjerodekool.nabu.tree.statement.StatementTree;

public abstract class StatementTreeBuilder<E extends StatementTree, SB extends StatementTreeBuilder<E, SB>> extends TreeBuilder<E, SB> {

    protected StatementTreeBuilder(final E original) {
        super(original);
    }

    protected StatementTreeBuilder() {
        super();
    }
}

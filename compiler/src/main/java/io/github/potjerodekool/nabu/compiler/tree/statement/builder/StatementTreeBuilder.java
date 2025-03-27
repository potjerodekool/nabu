package io.github.potjerodekool.nabu.compiler.tree.statement.builder;

import io.github.potjerodekool.nabu.compiler.tree.builder.TreeBuilder;
import io.github.potjerodekool.nabu.compiler.tree.statement.StatementTree;

public abstract class StatementTreeBuilder<E extends StatementTree, SB extends StatementTreeBuilder<E, SB>> extends TreeBuilder<E, SB> {

    protected StatementTreeBuilder(final E original) {
        super(original);
    }

    protected StatementTreeBuilder() {
        super();
    }
}

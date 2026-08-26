package io.github.potjerodekool.nabu.tree.statement.builder;

import io.github.potjerodekool.nabu.tree.Tree;
import io.github.potjerodekool.nabu.tree.builder.TreeBuilder;
import io.github.potjerodekool.nabu.tree.statement.StatementTree;

/**
 * Base class for statement builders.
 * @param <SB> Type of StatementTreeBuilder
 */
public class StatementTreeBuilder<SB extends StatementTreeBuilder<SB>> extends TreeBuilder<SB> {

    private final StatementTree original;

    public StatementTreeBuilder(StatementTree original) {
        super(original);
        this.original = original;
    }

    protected StatementTreeBuilder() {
        super();
        this.original = null;
    }

    @Override
    public SB self() {
        return (SB) this;
    }

    @Override
    public Tree build() {
        return original;
    }
}

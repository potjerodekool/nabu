package io.github.potjerodekool.nabu.compiler.tree.statement.builder;

import io.github.potjerodekool.nabu.compiler.tree.CatchTree;
import io.github.potjerodekool.nabu.compiler.tree.Tree;
import io.github.potjerodekool.nabu.compiler.tree.statement.BlockStatementTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.impl.CTryStatementTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.TryStatementTree;

import java.util.List;
import java.util.Objects;

public class TryStatementTreeBuilder extends StatementTreeBuilder<TryStatementTree, TryStatementTreeBuilder> {

    private BlockStatementTree body;
    private List<CatchTree> catchers;
    private BlockStatementTree finalizer;
    private List<Tree> resources;

    @Override
    public TryStatementTreeBuilder self() {
        return this;
    }

    public BlockStatementTree getBody() {
        return body;
    }

    public TryStatementTreeBuilder body(final BlockStatementTree body) {
        this.body = body;
        return this;
    }

    public List<CatchTree> getCatchers() {
        return Objects.requireNonNullElseGet(catchers, List::of);
    }

    public TryStatementTreeBuilder catchers(final List<CatchTree> catchers) {
        this.catchers = List.copyOf(catchers);
        return this;
    }

    public BlockStatementTree getFinalizer() {
        return finalizer;
    }

    public TryStatementTreeBuilder finalizer(final BlockStatementTree finalizer) {
        this.finalizer = finalizer;
        return this;
    }

    public List<Tree> getResources() {
        return Objects.requireNonNullElseGet(resources, List::of);
    }

    public TryStatementTreeBuilder resources(final List<Tree> resources) {
        this.resources = List.copyOf(resources);
        return this;
    }

    @Override
    public TryStatementTree build() {
        return new CTryStatementTree(this);
    }
}

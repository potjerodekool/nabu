package io.github.potjerodekool.nabu.tree.statement.impl;

import io.github.potjerodekool.nabu.tree.CatchTree;
import io.github.potjerodekool.nabu.tree.Tree;
import io.github.potjerodekool.nabu.tree.TreeVisitor;
import io.github.potjerodekool.nabu.tree.statement.BlockStatementTree;
import io.github.potjerodekool.nabu.tree.statement.TryStatementTree;
import io.github.potjerodekool.nabu.tree.statement.builder.StatementTreeBuilder;
import io.github.potjerodekool.nabu.tree.statement.builder.TryStatementTreeBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * Implementation of TryStatementTree
 */
public class CTryStatementTree extends CStatementTree implements TryStatementTree {

    private final BlockStatementTree body;
    private final List<CatchTree> catchers = new ArrayList<>();
    private final BlockStatementTree finalizer;
    private final List<Tree> resources = new ArrayList<>();

    public CTryStatementTree(final BlockStatementTree body,
                             final List<CatchTree> catchers,
                             final BlockStatementTree finalizer,
                             final List<Tree> resources,
                             final int lineNumber,
                             final int columnNumber) {
        super(lineNumber, columnNumber);
        this.body = body;
        this.catchers.addAll(catchers);
        this.finalizer = finalizer;
        this.resources.addAll(resources);
    }

    public CTryStatementTree(final TryStatementTreeBuilder tryStatementTreeBuilder) {
        super(tryStatementTreeBuilder);
        this.body = tryStatementTreeBuilder.getBody();
        this.catchers.addAll(tryStatementTreeBuilder.getCatchers());
        this.finalizer = tryStatementTreeBuilder.getFinalizer();
        this.resources.addAll(tryStatementTreeBuilder.getResources());
    }

    @Override
    public BlockStatementTree getBody() {
        return body;
    }

    @Override
    public List<CatchTree> getCatchers() {
        return catchers;
    }

    @Override
    public BlockStatementTree getFinalizer() {
        return finalizer;
    }

    @Override
    public List<Tree> getResources() {
        return resources;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitTryStatement(this, param);
    }

    @Override
    public StatementTreeBuilder<?> builder() {
        return new StatementTreeBuilder<>(this);
    }
}

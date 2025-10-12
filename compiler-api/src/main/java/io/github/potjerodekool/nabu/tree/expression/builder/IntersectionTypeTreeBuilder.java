package io.github.potjerodekool.nabu.tree.expression.builder;


import io.github.potjerodekool.nabu.tree.Tree;
import io.github.potjerodekool.nabu.tree.expression.IntersectionTypeTree;
import io.github.potjerodekool.nabu.tree.expression.impl.CIntersectionTypeTree;

import java.util.ArrayList;
import java.util.List;

public class IntersectionTypeTreeBuilder extends ExpressionBuilder<IntersectionTypeTree, IntersectionTypeTreeBuilder> {

    private final List<Tree> bounds = new ArrayList<>();

    @Override
    public IntersectionTypeTreeBuilder self() {
        return this;
    }

    public IntersectionTypeTreeBuilder bounds(final List<? extends Tree> bounds) {
        this.bounds.clear();
        this.bounds.addAll(bounds);
        return this;
    }

    public List<? extends Tree> getBounds() {
        return bounds;
    }

    @Override
    public IntersectionTypeTree build() {
        return new CIntersectionTypeTree(this);
    }
}

package io.github.potjerodekool.nabu.compiler.tree.expression.impl;

import io.github.potjerodekool.nabu.compiler.tree.Tree;
import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.expression.IntersectionTypeTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.builder.IntersectionTypeTreeBuilder;

import java.util.ArrayList;
import java.util.List;

public class CIntersectionTypeTree extends CExpressionTree implements IntersectionTypeTree {

    private final List<Tree> bounds = new ArrayList<>();

    public CIntersectionTypeTree(final List<? extends Tree> bounds,
                                 final int lineNumber,
                                 final int columnNumber) {
        super(lineNumber, columnNumber);
        this.bounds.addAll(bounds);
    }

    public CIntersectionTypeTree(final IntersectionTypeTreeBuilder intersectionTypeTreeBuilder) {
        super(intersectionTypeTreeBuilder);
        this.bounds.addAll(intersectionTypeTreeBuilder.getBounds());
    }

    @Override
    public List<? extends Tree> getBounds() {
        return bounds;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitIntersectionType(this, param);
    }
}

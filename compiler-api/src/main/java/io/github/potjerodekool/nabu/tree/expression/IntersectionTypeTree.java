package io.github.potjerodekool.nabu.tree.expression;

import io.github.potjerodekool.nabu.tree.Tree;

import java.util.List;

public interface IntersectionTypeTree extends ExpressionTree {

    List<? extends Tree> getBounds();
}

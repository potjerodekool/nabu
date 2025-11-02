package io.github.potjerodekool.nabu.tree.expression;

import io.github.potjerodekool.nabu.tree.Tree;

import java.util.List;

/**
 * Intersection type.
 * For example: Dog &amp; CanTalk
 */
public interface IntersectionTypeTree extends ExpressionTree {

    List<? extends Tree> getBounds();
}

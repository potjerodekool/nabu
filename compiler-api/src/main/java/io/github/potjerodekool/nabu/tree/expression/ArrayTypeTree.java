package io.github.potjerodekool.nabu.tree.expression;

import io.github.potjerodekool.nabu.tree.Tree;

import java.util.List;

public interface ArrayTypeTree extends ExpressionTree {

    Tree getComponentType();

    List<Dimension> getDimensions();
}

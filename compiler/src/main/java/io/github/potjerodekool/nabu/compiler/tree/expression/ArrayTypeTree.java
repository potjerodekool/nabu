package io.github.potjerodekool.nabu.compiler.tree.expression;

import io.github.potjerodekool.nabu.compiler.tree.Tree;

import java.util.List;

public interface ArrayTypeTree extends ExpressionTree {

    Tree getComponentType();

    List<Dimension> getDimensions();
}

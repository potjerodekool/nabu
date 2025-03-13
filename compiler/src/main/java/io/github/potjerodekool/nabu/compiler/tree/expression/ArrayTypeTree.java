package io.github.potjerodekool.nabu.compiler.tree.expression;

import io.github.potjerodekool.nabu.compiler.tree.Tree;

public interface ArrayTypeTree extends ExpressionTree {

    Tree getComponentType();

}

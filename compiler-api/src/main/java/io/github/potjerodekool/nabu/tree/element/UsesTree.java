package io.github.potjerodekool.nabu.tree.element;

import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;

public interface UsesTree extends DirectiveTree {

    ExpressionTree getServiceName();

}

package io.github.potjerodekool.nabu.compiler.tree.element;

import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;

public interface UsesTree extends DirectiveTree {

    ExpressionTree getServiceName();

}

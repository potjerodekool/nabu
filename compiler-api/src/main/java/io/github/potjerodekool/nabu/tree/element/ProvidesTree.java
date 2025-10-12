package io.github.potjerodekool.nabu.tree.element;

import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;

import java.util.List;

public interface ProvidesTree extends DirectiveTree {

    ExpressionTree getServiceName();

    List<? extends ExpressionTree> getImplementationNames();
}

package io.github.potjerodekool.nabu.compiler.tree.element;

import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;

import java.util.List;

public interface ProvidesTree extends DirectiveTree {

    ExpressionTree getServiceName();

    List<? extends ExpressionTree> getImplementationNames();
}

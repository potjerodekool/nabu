package io.github.potjerodekool.nabu.tree.element;

import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;

import java.util.List;

public interface OpensTree extends DirectiveTree {

    ExpressionTree getPackageName();

    List<? extends ExpressionTree> getModuleNames();
}

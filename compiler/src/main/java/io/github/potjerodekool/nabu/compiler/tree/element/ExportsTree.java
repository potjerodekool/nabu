package io.github.potjerodekool.nabu.compiler.tree.element;

import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;

import java.util.List;

public interface ExportsTree extends DirectiveTree {

    ExpressionTree getPackageName();

    List<? extends ExpressionTree> getModuleNames();
}

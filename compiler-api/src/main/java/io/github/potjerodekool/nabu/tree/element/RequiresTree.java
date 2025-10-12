package io.github.potjerodekool.nabu.tree.element;

import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;

public interface RequiresTree extends DirectiveTree {
    boolean isStatic();

    boolean isTransitive();

    ExpressionTree getModuleName();

}

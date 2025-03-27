package io.github.potjerodekool.nabu.compiler.tree.element;

import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;

public interface RequiresTree extends DirectiveTree {
    boolean isStatic();

    boolean isTransitive();

    ExpressionTree getModuleName();

}

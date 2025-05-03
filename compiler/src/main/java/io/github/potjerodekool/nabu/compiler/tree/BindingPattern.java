package io.github.potjerodekool.nabu.compiler.tree;

import io.github.potjerodekool.nabu.compiler.tree.statement.VariableDeclaratorTree;

public interface BindingPattern extends Pattern {
    VariableDeclaratorTree getVariableDeclarator();
}

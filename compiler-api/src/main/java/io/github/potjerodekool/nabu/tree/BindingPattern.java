package io.github.potjerodekool.nabu.tree;

import io.github.potjerodekool.nabu.tree.statement.VariableDeclaratorTree;

public interface BindingPattern extends Pattern {
    VariableDeclaratorTree getVariableDeclarator();
}

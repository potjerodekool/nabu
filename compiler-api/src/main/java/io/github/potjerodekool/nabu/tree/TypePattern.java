package io.github.potjerodekool.nabu.tree;

import io.github.potjerodekool.nabu.tree.impl.CTypePattern;
import io.github.potjerodekool.nabu.tree.statement.VariableDeclaratorTree;

/**
 * A type pattern.
 * a instance Integer.
 */
public sealed interface TypePattern extends Pattern permits CTypePattern {
    VariableDeclaratorTree getVariableDeclarator();
}

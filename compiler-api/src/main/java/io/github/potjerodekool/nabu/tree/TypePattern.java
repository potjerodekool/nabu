package io.github.potjerodekool.nabu.tree;

import io.github.potjerodekool.nabu.tree.impl.CTypePattern;
import io.github.potjerodekool.nabu.tree.statement.VariableDeclaratorTree;

/**
 * A type pattern.
 * An instance Integer.
 */
public sealed interface TypePattern extends Pattern permits CTypePattern {
    VariableDeclaratorTree getVariableDeclarator();

    static TypePattern create(final VariableDeclaratorTree variableDeclarator,
                              final int lineNumber, final int columnNumber) {
        return new  CTypePattern(variableDeclarator, lineNumber, columnNumber);
    }
}

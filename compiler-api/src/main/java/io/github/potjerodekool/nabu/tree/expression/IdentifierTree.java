package io.github.potjerodekool.nabu.tree.expression;

import io.github.potjerodekool.nabu.tree.expression.impl.CIdentifierTree;

/**
 * Identifier expression.
 */
public interface IdentifierTree extends ExpressionTree {

    String getName();

    static IdentifierTree create(final String name) {
        return new CIdentifierTree(name, -1, -1);
    }
}

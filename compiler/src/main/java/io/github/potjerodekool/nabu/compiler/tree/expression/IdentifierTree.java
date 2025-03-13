package io.github.potjerodekool.nabu.compiler.tree.expression;

import io.github.potjerodekool.nabu.compiler.tree.expression.impl.CIdentifierTree;

public interface IdentifierTree extends ExpressionTree, Identifier {

    static IdentifierTree create(final String name) {
        return new CIdentifierTree(name, -1, -1);
    }
}

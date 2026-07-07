package io.github.potjerodekool.nabu.tree;

import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.impl.CErrorTree;

/**
 * An error tree.
 */
public interface ErrorTree extends ExpressionTree {

    static ErrorTree create(final int lineNumber,
                            final int columnNumber) {
        return new CErrorTree(lineNumber, columnNumber);
    }
}

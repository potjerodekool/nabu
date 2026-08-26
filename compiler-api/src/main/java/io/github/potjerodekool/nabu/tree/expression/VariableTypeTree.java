package io.github.potjerodekool.nabu.tree.expression;

import io.github.potjerodekool.nabu.tree.expression.impl.CVariableTypeTree;

/**
 * Represent a variable type where the 'var' keyword is used instead of the type.
 */
public interface VariableTypeTree extends ExpressionTree {

    static VariableTypeTree create(final int lineNumber,
                                   final int columnNumber) {
        return new CVariableTypeTree(lineNumber, columnNumber);
    }

}

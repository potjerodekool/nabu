package io.github.potjerodekool.nabu.tree.element;

import io.github.potjerodekool.nabu.tree.element.impl.CUsesTree;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;

/**
 * Represents an 'uses' in a module.
 * For example:
 * <p> </p>
 * module MyModule {
 *  uses foo.Bar;
 * }
 */
public sealed interface UsesTree extends DirectiveTree permits CUsesTree {

    ExpressionTree getServiceName();

    static UsesTree create(final ExpressionTree serviceName,
                           final int lineNumber,
                           final int columnNumber) {
        return new CUsesTree(serviceName, lineNumber, columnNumber);
    }
}

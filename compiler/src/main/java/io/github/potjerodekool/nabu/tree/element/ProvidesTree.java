package io.github.potjerodekool.nabu.tree.element;

import io.github.potjerodekool.nabu.tree.element.impl.CProvidesTree;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;

import java.util.List;

/**
 * Represents a provides in a module.
 * For exmaple:
 * <p> </p>
 * module MyModule {
 *  provides FileReader with MyCustomFileReader;
 * }
 */
public sealed interface ProvidesTree extends DirectiveTree permits CProvidesTree {

    ExpressionTree getServiceName();

    List<? extends ExpressionTree> getImplementationNames();

    static ProvidesTree create(final ExpressionTree serviceName,
                               final List<ExpressionTree> implementations,
                               final int lineNumber, final int columnNumber) {
        return new CProvidesTree(serviceName, implementations, lineNumber, columnNumber);
    }
}

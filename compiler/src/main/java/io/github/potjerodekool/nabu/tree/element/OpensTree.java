package io.github.potjerodekool.nabu.tree.element;

import io.github.potjerodekool.nabu.tree.element.impl.COpensTree;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.IdentifierTree;

import java.util.List;

/**
 * Represents an opens is a module:
 * For example:
 * <p> </p>
 * module MyModule {
 *    opens myPackage;
 * }
 */
public sealed interface OpensTree extends DirectiveTree permits COpensTree {

    ExpressionTree getPackageName();

    List<? extends ExpressionTree> getModuleNames();

    static OpensTree create(final IdentifierTree packageName,
                            final List<ExpressionTree> moduleNames,
                            final int lineNumber,
                            final int columnNumber) {
        return new COpensTree(packageName, moduleNames, lineNumber, columnNumber);
    }
}

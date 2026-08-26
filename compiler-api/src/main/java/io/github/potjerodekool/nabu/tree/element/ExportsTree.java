package io.github.potjerodekool.nabu.tree.element;

import io.github.potjerodekool.nabu.tree.element.impl.CExportsTree;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.IdentifierTree;

import java.util.List;

/**
 * Represents an exports in a module.
 * For example:
 * <p> </p>
 * module MyModule {
 *   exports myPackage;
 * }
 */
public sealed interface ExportsTree extends DirectiveTree permits CExportsTree {

    ExpressionTree getPackageName();

    List<? extends ExpressionTree> getModuleNames();

    static ExportsTree create(final IdentifierTree packageName,
                              final List<ExpressionTree> moduleNames,
                              final int lineNumber,
                              final int columnNumber) {
        return new CExportsTree(packageName, moduleNames, lineNumber, columnNumber);
    }
}

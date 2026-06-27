package io.github.potjerodekool.nabu.tree.element;

import io.github.potjerodekool.nabu.tree.element.impl.CExportsTree;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;

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
}

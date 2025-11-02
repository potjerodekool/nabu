package io.github.potjerodekool.nabu.tree.element;

import io.github.potjerodekool.nabu.tree.element.impl.COpensTree;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;

import java.util.List;

/**
 * Represents a opens is a module:
 * For example:
 * <p> </p>
 * module MyModule {
 *    opens myPackage;
 * }
 */
public sealed interface OpensTree extends DirectiveTree permits COpensTree {

    ExpressionTree getPackageName();

    List<? extends ExpressionTree> getModuleNames();
}

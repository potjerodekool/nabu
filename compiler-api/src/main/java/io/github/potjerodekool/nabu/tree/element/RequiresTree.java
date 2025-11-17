package io.github.potjerodekool.nabu.tree.element;

import io.github.potjerodekool.nabu.tree.element.impl.CRequiresTree;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;

/**
 * Represents a 'requires' in a module.
 * For example:
 * module MyModule {
 *     requires java.sql;
 * }
 */
public sealed interface RequiresTree extends DirectiveTree permits CRequiresTree {
    boolean isStatic();

    boolean isTransitive();

    ExpressionTree getModuleName();

}

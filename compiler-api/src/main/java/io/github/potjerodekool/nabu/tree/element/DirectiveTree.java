package io.github.potjerodekool.nabu.tree.element;

import io.github.potjerodekool.nabu.tree.Tree;
import io.github.potjerodekool.nabu.tree.element.impl.CDirective;

/**
 * Base interface for directives in a module.
 */
public sealed interface DirectiveTree extends Tree permits ExportsTree, OpensTree, ProvidesTree, RequiresTree, UsesTree, CDirective {
}

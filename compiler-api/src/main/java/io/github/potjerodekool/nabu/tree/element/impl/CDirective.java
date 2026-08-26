package io.github.potjerodekool.nabu.tree.element.impl;

import io.github.potjerodekool.nabu.tree.element.DirectiveTree;
import io.github.potjerodekool.nabu.tree.impl.CTree;

/**
 * Base class for module directives.
 */
public sealed abstract class CDirective extends CTree implements DirectiveTree permits CExportsTree, COpensTree, CProvidesTree, CRequiresTree, CUsesTree {

    public CDirective(final int lineNumber, final int columnNumber) {
        super(lineNumber, columnNumber);
    }
}

package io.github.potjerodekool.nabu.tree.element.impl;

import io.github.potjerodekool.nabu.tree.element.DirectiveTree;
import io.github.potjerodekool.nabu.tree.impl.CTree;

public abstract class CDirective extends CTree implements DirectiveTree {

    public CDirective(final int lineNumber, final int columnNumber) {
        super(lineNumber, columnNumber);
    }
}

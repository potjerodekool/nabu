package io.github.potjerodekool.nabu.tree.impl;

import io.github.potjerodekool.nabu.tree.Pattern;

public abstract class CPattern extends CTree implements Pattern {

    public CPattern(final int lineNumber, final int columnNumber) {
        super(lineNumber, columnNumber);
    }
}

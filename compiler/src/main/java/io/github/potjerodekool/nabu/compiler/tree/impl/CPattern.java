package io.github.potjerodekool.nabu.compiler.tree.impl;

import io.github.potjerodekool.nabu.compiler.tree.Pattern;

public abstract class CPattern extends CTree implements Pattern {

    public CPattern(final int lineNumber, final int columnNumber) {
        super(lineNumber, columnNumber);
    }
}

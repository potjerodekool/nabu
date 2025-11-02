package io.github.potjerodekool.nabu.tree.impl;

import io.github.potjerodekool.nabu.tree.Pattern;

/**
 * Base class for patterns.
 */
public abstract sealed class CPattern extends CTree implements Pattern permits CTypePattern {

    public CPattern(final int lineNumber, final int columnNumber) {
        super(lineNumber, columnNumber);
    }
}

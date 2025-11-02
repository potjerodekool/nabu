package io.github.potjerodekool.nabu.tree.impl;

import io.github.potjerodekool.nabu.tree.CaseLabel;

/**
 * Base class for case labels.
 */
public abstract sealed class CCaseLabel extends CTree implements CaseLabel permits CConstantCaseLabel, CDefaultCaseLabel {
    public CCaseLabel(final int lineNumber, final int columnNumber) {
        super(lineNumber, columnNumber);
    }

}

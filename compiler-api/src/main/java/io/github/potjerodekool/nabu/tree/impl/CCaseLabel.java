package io.github.potjerodekool.nabu.tree.impl;

import io.github.potjerodekool.nabu.tree.CaseLabel;

public abstract class CCaseLabel extends CTree implements CaseLabel {
    public CCaseLabel(final int lineNumber, final int columnNumber) {
        super(lineNumber, columnNumber);
    }

}

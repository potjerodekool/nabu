package io.github.potjerodekool.nabu.compiler.tree.impl;

import io.github.potjerodekool.nabu.compiler.tree.CaseLabel;

public abstract class CCaseLabel extends CTree implements CaseLabel {
    public CCaseLabel(final int lineNumber, final int columnNumber) {
        super(lineNumber, columnNumber);
    }

}

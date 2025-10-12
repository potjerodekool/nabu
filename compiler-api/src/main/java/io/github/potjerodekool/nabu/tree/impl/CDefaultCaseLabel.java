package io.github.potjerodekool.nabu.tree.impl;


import io.github.potjerodekool.nabu.tree.DefaultCaseLabel;
import io.github.potjerodekool.nabu.tree.TreeVisitor;

public class CDefaultCaseLabel extends CCaseLabel implements DefaultCaseLabel {

    public CDefaultCaseLabel(final int lineNumber,
                             final int columnNumber) {
        super(lineNumber, columnNumber);
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitDefaultCaseLabel(this, param);
    }
}

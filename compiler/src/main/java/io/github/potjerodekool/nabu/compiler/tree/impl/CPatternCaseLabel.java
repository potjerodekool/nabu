package io.github.potjerodekool.nabu.compiler.tree.impl;

import io.github.potjerodekool.nabu.compiler.tree.PatternCaseLabel;
import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;

public class CPatternCaseLabel extends CTree implements PatternCaseLabel {

    private final CPattern pattern;

    public CPatternCaseLabel(final CPattern pattern,
                             final int lineNumber,
                             final int columnNumber) {
        super(lineNumber, columnNumber);
        this.pattern = pattern;
    }

    @Override
    public CPattern getPattern() {
        return pattern;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitPatternCaseLabel(this, param);
    }
}

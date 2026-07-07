package io.github.potjerodekool.nabu.tree.impl;


import io.github.potjerodekool.nabu.tree.Pattern;
import io.github.potjerodekool.nabu.tree.PatternCaseLabel;
import io.github.potjerodekool.nabu.tree.TreeVisitor;

/**
 * Implementation of PatternCaseLabel.
 */
public final class CPatternCaseLabel extends CTree implements PatternCaseLabel {

    private final Pattern pattern;

    public CPatternCaseLabel(final Pattern pattern,
                             final int lineNumber,
                             final int columnNumber) {
        super(lineNumber, columnNumber);
        this.pattern = pattern;
    }

    @Override
    public Pattern getPattern() {
        return pattern;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitPatternCaseLabel(this, param);
    }
}

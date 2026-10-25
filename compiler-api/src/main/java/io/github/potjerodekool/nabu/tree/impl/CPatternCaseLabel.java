package io.github.potjerodekool.nabu.tree.impl;


import io.github.potjerodekool.nabu.tree.Pattern;
import io.github.potjerodekool.nabu.tree.PatternCaseLabel;
import io.github.potjerodekool.nabu.tree.TreeVisitor;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;

/**
 * Implementation of PatternCaseLabel.
 */
public final class CPatternCaseLabel extends CTree implements PatternCaseLabel {

    private final Pattern pattern;
    private final ExpressionTree when;

    public CPatternCaseLabel(final Pattern pattern,
                             final ExpressionTree when,
                             final int lineNumber,
                             final int columnNumber) {
        super(lineNumber, columnNumber);
        this.pattern = pattern;
        this.when = when;
    }

    @Override
    public Pattern getPattern() {
        return pattern;
    }

    @Override
    public ExpressionTree getWhen() {
        return when;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitPatternCaseLabel(this, param);
    }
}

package io.github.potjerodekool.nabu.tree;

import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.impl.CPatternCaseLabel;

/**
 * A pattern case label.
 * <p> {@code case Point p when p.x() > 0:</p>
 */
public sealed interface PatternCaseLabel extends CaseLabel permits CPatternCaseLabel {
    Pattern getPattern();

    /**
     * The guard of the pattern ({@code when expression}), or {@code null}
     * when the pattern has no guard.
     */
    ExpressionTree getWhen();

    static PatternCaseLabel create(final Pattern pattern,
                                   final int lineNumber,
                                   final int columnNumber) {
        return new CPatternCaseLabel(pattern, null, lineNumber, columnNumber);
    }

    static PatternCaseLabel create(final Pattern pattern,
                                   final ExpressionTree when,
                                   final int lineNumber,
                                   final int columnNumber) {
        return new CPatternCaseLabel(pattern, when, lineNumber, columnNumber);
    }
}

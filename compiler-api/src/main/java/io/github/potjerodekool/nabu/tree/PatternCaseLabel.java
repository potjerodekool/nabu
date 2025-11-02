package io.github.potjerodekool.nabu.tree;

import io.github.potjerodekool.nabu.tree.impl.CPatternCaseLabel;

/**
 * A pattern case label
 * <p> </p>
 * case value instanceof Integer
 */
public sealed interface PatternCaseLabel extends CaseLabel permits CPatternCaseLabel {
    Pattern getPattern();
}

package io.github.potjerodekool.nabu.tree;

import io.github.potjerodekool.nabu.tree.impl.CCaseLabel;

public sealed interface CaseLabel extends Tree permits ConstantCaseLabel, DefaultCaseLabel, PatternCaseLabel, CCaseLabel {
}

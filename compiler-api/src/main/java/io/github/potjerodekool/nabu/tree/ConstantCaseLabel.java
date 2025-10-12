package io.github.potjerodekool.nabu.tree;

import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;

public interface ConstantCaseLabel extends CaseLabel {
    ExpressionTree getExpression();
}

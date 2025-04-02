package io.github.potjerodekool.nabu.compiler.tree;

import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;

public interface ConstantCaseLabel extends CaseLabel {
    ExpressionTree getExpression();
}

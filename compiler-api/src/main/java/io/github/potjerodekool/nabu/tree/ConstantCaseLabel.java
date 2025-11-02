package io.github.potjerodekool.nabu.tree;

import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.impl.CConstantCaseLabel;

/**
 * A constant case label.
 * <p> </p>
 * switch (value) {
 *     case 10 -> {
 *  <p> </p>
 *     }
 * }
 */
public sealed interface ConstantCaseLabel extends CaseLabel permits CConstantCaseLabel {
    ExpressionTree getExpression();
}

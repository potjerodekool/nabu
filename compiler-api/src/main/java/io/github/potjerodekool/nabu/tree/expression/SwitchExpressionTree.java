package io.github.potjerodekool.nabu.tree.expression;

import io.github.potjerodekool.nabu.tree.expression.builder.SwitchExpressionTreeBuilder;
import io.github.potjerodekool.nabu.tree.statement.CaseStatement;

import java.util.List;

/**
 * A switch expression (JLS 15.28).
 */
public interface SwitchExpressionTree extends ExpressionTree {

    /**
     * @return Returns the expression where is switched on.
     */
    ExpressionTree getSelector();

    /**
     * @return Returns the cases of the switch.
     */
    List<CaseStatement> getCases();

    @Override
    SwitchExpressionTreeBuilder builder();

}

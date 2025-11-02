package io.github.potjerodekool.nabu.tree.statement;

import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.statement.builder.SwitchStatementBuilder;

import java.util.List;

/**
 * Swtich statement.
 */
public interface SwitchStatement extends StatementTree {

    /**
     * @return Return the expression where there is switched on.
     */
    ExpressionTree getSelector();

    /**
     * @return Returns the cases of the switch.
     */
    List<CaseStatement> getCases();

    /**
     * See {@link StatementTree#builder()}
     */
    SwitchStatementBuilder builder();
}

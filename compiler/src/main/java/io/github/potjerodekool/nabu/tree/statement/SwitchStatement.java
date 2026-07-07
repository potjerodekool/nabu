package io.github.potjerodekool.nabu.tree.statement;

import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.statement.builder.SwitchStatementBuilder;
import io.github.potjerodekool.nabu.tree.statement.impl.CSwitchStatement;

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

    static SwitchStatement create(final ExpressionTree selector,
                                  final List<CaseStatement> cases,
                                  final int lineNumber,
                                  final int columnNumber) {
        return new CSwitchStatement(selector, cases, lineNumber, columnNumber);
    }
}

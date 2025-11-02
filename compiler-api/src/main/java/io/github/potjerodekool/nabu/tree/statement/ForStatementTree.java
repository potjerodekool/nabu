package io.github.potjerodekool.nabu.tree.statement;

import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.statement.builder.ForStatementTreeBuilder;

import java.util.List;

/**
 * For statement.
 */
public interface ForStatementTree extends StatementTree {

    /**
     * @return Returns the init statements.
     */
    List<StatementTree> getForInit();

    /**
     * @return Returns the condition expression.
     */
    ExpressionTree getCondition();

    /**
     * @return Returns the update statements.
     */
    List<StatementTree> getForUpdate();

    /**
     * @return Returns the body statement.
     */
    StatementTree getStatement();

    /**
     * See {@link StatementTree#builder()}
     */
    ForStatementTreeBuilder builder();
}

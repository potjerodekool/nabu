package io.github.potjerodekool.nabu.tree.statement;

import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.statement.builder.DoWhileStatementTreeBuilder;

/**
 * Do while statement.
 */
public interface DoWhileStatementTree extends StatementTree {

    /**
     * @return Returns the body of the loop.
     */
    StatementTree getBody();

    /**
     * @return Returns the condition of the loop.
     */
    ExpressionTree getCondition();

    /**
     * See {@link StatementTree#builder()}
     */
    DoWhileStatementTreeBuilder builder();
}

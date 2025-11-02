package io.github.potjerodekool.nabu.tree.statement;

import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.statement.builder.WhileStatementTreeBuilder;
import io.github.potjerodekool.nabu.tree.statement.impl.CWhileStatementTree;

/**
 * A while statement.
 */
public sealed interface WhileStatementTree extends StatementTree permits CWhileStatementTree {

    /**
     * @return Returns the condition.
     */
    ExpressionTree getCondition();

    /**
     * @return Returns the body.
     */
    StatementTree getBody();

    /**
     * @return Returns a builder to build a new While statement based on this one.
     */
    WhileStatementTreeBuilder builder();
}

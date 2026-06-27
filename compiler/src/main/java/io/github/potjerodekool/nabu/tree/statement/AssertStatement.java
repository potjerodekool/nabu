package io.github.potjerodekool.nabu.tree.statement;

import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;

/**
 * Assert statement.
 * Connection connection = getConnection();
 * assert connection != null : "Connection is null";
 */
public interface AssertStatement extends StatementTree {

    /**
     * @return Returns the condition of the asset.
     */
    ExpressionTree getCondition();

    /**
     * @return Returns the detail message of the asserting.
     */
    ExpressionTree getDetail();

}

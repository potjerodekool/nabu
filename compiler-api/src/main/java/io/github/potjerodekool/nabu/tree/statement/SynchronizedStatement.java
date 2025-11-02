package io.github.potjerodekool.nabu.tree.statement;

import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;

/**
 * Synchronized statement
 */
public interface SynchronizedStatement extends StatementTree {

    /**
     * @return Returns the expression where is synchronized on.
     */
    ExpressionTree getExpression();

    /**
     * @return Returns the body of the synchronization.
     */
    BlockStatementTree getBody();
}

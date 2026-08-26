package io.github.potjerodekool.nabu.tree.statement;

import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.statement.impl.CSynchronizedStatement;

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

    static SynchronizedStatement create(final ExpressionTree expression,
                                        final BlockStatementTree body,
                                        final int lineNumber,
                                        final int columnNumber) {
        return new CSynchronizedStatement(expression, body, lineNumber, columnNumber);
    }
}

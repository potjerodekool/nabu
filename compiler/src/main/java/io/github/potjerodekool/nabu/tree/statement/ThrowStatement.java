package io.github.potjerodekool.nabu.tree.statement;

import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;

/**
 * Throw statement.
 */
public interface ThrowStatement extends StatementTree {

    /**
     * @return Returns the expression of the throw.
     */
    ExpressionTree getExpression();
}

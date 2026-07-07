package io.github.potjerodekool.nabu.tree.statement;

import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.statement.impl.CThrowStatement;

/**
 * Throw statement.
 */
public interface ThrowStatement extends StatementTree {

    /**
     * @return Returns the expression of the throw.
     */
    ExpressionTree getExpression();

    static ThrowStatement create(final ExpressionTree expression,
                                 final int lineNumber,
                                 final int columnNumber) {
        return new CThrowStatement(expression, lineNumber, columnNumber);
    }
}

package io.github.potjerodekool.nabu.tree.statement;

import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.statement.builder.ReturnStatementTreeBuilder;

/**
 * Return statement.
 */
public interface ReturnStatementTree extends StatementTree {

    /**
     * @return Return the expression that is returned which may be null.
     */
    ExpressionTree getExpression();

    /**
     * See {@link StatementTree#builder()}
     */
    ReturnStatementTreeBuilder builder();
}

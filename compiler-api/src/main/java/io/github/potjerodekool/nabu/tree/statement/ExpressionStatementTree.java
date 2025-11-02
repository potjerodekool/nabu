package io.github.potjerodekool.nabu.tree.statement;

import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.statement.builder.StatementExpressionTreeBuilder;

/**
 * Expression statement.
 */
public interface ExpressionStatementTree extends StatementTree {

    /**
     * @return Returns the expression.
     */
    ExpressionTree getExpression();

    /**
     * See {@link StatementTree#builder()}
     */
    StatementExpressionTreeBuilder builder();
}

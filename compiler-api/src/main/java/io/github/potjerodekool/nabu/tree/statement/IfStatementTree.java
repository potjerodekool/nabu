package io.github.potjerodekool.nabu.tree.statement;

import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.statement.builder.IfStatementTreeBuilder;

/**
 * If statement.
 */
public interface IfStatementTree extends StatementTree {

    /**
     * @return Returns the expression of the if statement.
     */
    ExpressionTree getExpression();

    /**
     * @return Returns the then statement.
     */
    StatementTree getThenStatement();

    /**
     * @return Returns the else statement which may be null.
     */
    StatementTree getElseStatement();

    /**
     * See {@link StatementTree#builder()}
     */
    IfStatementTreeBuilder builder();
}

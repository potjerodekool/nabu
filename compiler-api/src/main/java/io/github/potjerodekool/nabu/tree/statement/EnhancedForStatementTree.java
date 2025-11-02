package io.github.potjerodekool.nabu.tree.statement;

import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.statement.builder.EnhancedForStatementTreeBuilder;

/**
 * Enchanged for statement.
 */
public interface EnhancedForStatementTree extends StatementTree {

    /**
     * @return Returns the local variable.
     */
    VariableDeclaratorTree getLocalVariable();

    /**
     * @return Returns the expression that inits the local variable.
     */
    ExpressionTree getExpression();

    /**
     * @return Returns the body statement.
     */
    StatementTree getStatement();

    /**
     * See {@link StatementTree#builder()}
     */
    EnhancedForStatementTreeBuilder builder();
}

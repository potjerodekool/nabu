package io.github.potjerodekool.nabu.tree.statement;

import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.statement.impl.CYieldStatement;

/**
 * A yield statement.
 */
public sealed interface YieldStatement extends StatementTree permits CYieldStatement {

    /**
     * @return Returns the yield expression.
     */
    ExpressionTree getExpression();
}

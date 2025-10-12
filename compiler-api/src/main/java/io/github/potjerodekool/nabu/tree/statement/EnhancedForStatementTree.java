package io.github.potjerodekool.nabu.tree.statement;

import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.statement.builder.EnhancedForStatementTreeBuilder;

public interface EnhancedForStatementTree extends StatementTree {

    VariableDeclaratorTree getLocalVariable();

    ExpressionTree getExpression();

    StatementTree getStatement();

    EnhancedForStatementTreeBuilder builder();
}

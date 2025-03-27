package io.github.potjerodekool.nabu.compiler.tree.statement;

import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.builder.EnhancedForStatementTreeBuilder;

public interface EnhancedForStatementTree extends StatementTree {

    VariableDeclaratorTree getLocalVariable();

    ExpressionTree getExpression();

    StatementTree getStatement();

    EnhancedForStatementTreeBuilder builder();
}

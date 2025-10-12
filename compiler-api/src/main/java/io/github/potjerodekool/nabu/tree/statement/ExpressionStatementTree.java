package io.github.potjerodekool.nabu.tree.statement;

import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.statement.builder.StatementExpressionTreeBuilder;

public interface ExpressionStatementTree extends StatementTree {

    ExpressionTree getExpression();

    StatementExpressionTreeBuilder builder();
}

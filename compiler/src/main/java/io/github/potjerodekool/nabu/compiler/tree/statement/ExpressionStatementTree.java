package io.github.potjerodekool.nabu.compiler.tree.statement;

import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.builder.StatementExpressionTreeBuilder;

public interface ExpressionStatementTree extends StatementTree {

    ExpressionTree getExpression();

    StatementExpressionTreeBuilder builder();
}

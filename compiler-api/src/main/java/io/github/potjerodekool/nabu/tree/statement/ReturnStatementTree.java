package io.github.potjerodekool.nabu.tree.statement;

import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.statement.builder.ReturnStatementTreeBuilder;

public interface ReturnStatementTree extends StatementTree {

    ExpressionTree getExpression();

    ReturnStatementTreeBuilder builder();
}

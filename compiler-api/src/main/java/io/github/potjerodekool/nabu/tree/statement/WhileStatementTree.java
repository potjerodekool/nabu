package io.github.potjerodekool.nabu.tree.statement;

import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.statement.builder.WhileStatementTreeBuilder;

public interface WhileStatementTree extends StatementTree {

    ExpressionTree getCondition();

    StatementTree getBody();

    WhileStatementTreeBuilder builder();
}

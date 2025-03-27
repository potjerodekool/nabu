package io.github.potjerodekool.nabu.compiler.tree.statement;

import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.builder.WhileStatementTreeBuilder;

public interface WhileStatementTree extends StatementTree {

    ExpressionTree getCondition();

    StatementTree getBody();

    WhileStatementTreeBuilder builder();
}

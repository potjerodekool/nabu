package io.github.potjerodekool.nabu.tree.statement;

import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.statement.builder.DoWhileStatementTreeBuilder;

public interface DoWhileStatementTree extends StatementTree {

    StatementTree getBody();

    ExpressionTree getCondition();

    DoWhileStatementTreeBuilder builder();
}

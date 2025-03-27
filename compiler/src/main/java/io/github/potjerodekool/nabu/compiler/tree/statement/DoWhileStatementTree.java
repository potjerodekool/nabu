package io.github.potjerodekool.nabu.compiler.tree.statement;

import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.builder.DoWhileStatementTreeBuilder;

public interface DoWhileStatementTree extends StatementTree {

    StatementTree getBody();

    ExpressionTree getCondition();

    DoWhileStatementTreeBuilder builder();
}

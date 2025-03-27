package io.github.potjerodekool.nabu.compiler.tree.statement;

import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.builder.ForStatementTreeBuilder;

import java.util.List;

public interface ForStatementTree extends StatementTree {

    List<StatementTree> getForInit();

    ExpressionTree getExpression();

    List<StatementTree> getForUpdate();

    StatementTree getStatement();

    ForStatementTreeBuilder builder();
}

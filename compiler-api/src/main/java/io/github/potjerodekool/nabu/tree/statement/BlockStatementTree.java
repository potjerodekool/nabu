package io.github.potjerodekool.nabu.tree.statement;

import io.github.potjerodekool.nabu.tree.statement.builder.BlockStatementTreeBuilder;

import java.util.List;

public interface BlockStatementTree extends StatementTree {

    List<StatementTree> getStatements();

    void addStatement(final StatementTree statement);

    void addStatements(final List<StatementTree> statements);

    BlockStatementTreeBuilder builder();
}

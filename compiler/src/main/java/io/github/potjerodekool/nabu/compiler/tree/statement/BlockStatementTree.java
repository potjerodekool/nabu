package io.github.potjerodekool.nabu.compiler.tree.statement;

import io.github.potjerodekool.nabu.compiler.tree.statement.builder.BlockStatementTreeBuilder;

import java.util.List;

public interface BlockStatementTree extends StatementTree {

    List<StatementTree> getStatements();

    void addStatement(final StatementTree statement);

    BlockStatementTreeBuilder builder();
}

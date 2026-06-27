package io.github.potjerodekool.nabu.tree.statement.builder;


import io.github.potjerodekool.nabu.tree.statement.BlockStatementTree;
import io.github.potjerodekool.nabu.tree.statement.StatementTree;
import io.github.potjerodekool.nabu.tree.statement.impl.CBlockStatementTree;

import java.util.ArrayList;
import java.util.List;

/**
 * Builder for Block statements.
 */
public class BlockStatementTreeBuilder extends StatementTreeBuilder<BlockStatementTreeBuilder> {

    private final List<StatementTree> statements = new ArrayList<>();

    public BlockStatementTreeBuilder(final BlockStatementTree original) {
        super(original);
    }

    @Override
    public BlockStatementTreeBuilder self() {
        return this;
    }

    public BlockStatementTreeBuilder statements(final List<StatementTree> statements) {
        this.statements.addAll(statements);
        return this;
    }

    public List<StatementTree> getStatements() {
        return statements;
    }

    @Override
    public BlockStatementTree build() {
        return new CBlockStatementTree(this);
    }
}

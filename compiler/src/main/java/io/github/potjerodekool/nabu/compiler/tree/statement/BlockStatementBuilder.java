package io.github.potjerodekool.nabu.compiler.tree.statement;

import java.util.ArrayList;
import java.util.List;

public class BlockStatementBuilder extends StatementBuilder<BlockStatement> {

    final List<Statement> statements = new ArrayList<>();

    public BlockStatementBuilder(final BlockStatement original) {
        super(original);
    }

    @Override
    public BlockStatementBuilder self() {
        return this;
    }

    public BlockStatementBuilder statements(final List<Statement> statements) {
        this.statements.addAll(statements);
        return this;
    }

    @Override
    public BlockStatement build() {
        return new BlockStatement(this);
    }
}

package io.github.potjerodekool.nabu.compiler.tree.statement;

import java.util.List;

public interface BlockStatement extends Statement {

    List<Statement> getStatements();

    void addStatement(final Statement statement);

    BlockStatementBuilder builder();
}

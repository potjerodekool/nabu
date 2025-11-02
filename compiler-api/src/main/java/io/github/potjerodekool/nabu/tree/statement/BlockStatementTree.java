package io.github.potjerodekool.nabu.tree.statement;

import io.github.potjerodekool.nabu.tree.statement.builder.BlockStatementTreeBuilder;

import java.util.List;

/**
 * Block statement.
 */
public interface BlockStatementTree extends StatementTree {

    /**
     * @return Returns the statements of the block.
     */
    List<StatementTree> getStatements();

    /**
     * @param statement Statement to add.
     */
    void addStatement(final StatementTree statement);

    /**
     * @param statements Statements to add.
     */
    void addStatements(final List<StatementTree> statements);

    /**
     * See {@link StatementTree#builder()}
     */
    BlockStatementTreeBuilder builder();
}

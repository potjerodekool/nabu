package io.github.potjerodekool.nabu.tree.statement.impl;

import io.github.potjerodekool.nabu.tree.impl.CTree;
import io.github.potjerodekool.nabu.tree.statement.StatementTree;
import io.github.potjerodekool.nabu.tree.statement.builder.StatementTreeBuilder;

/**
 * Base class for Statements.
 */
public abstract class CStatementTree extends CTree implements StatementTree {

    protected CStatementTree(final int lineNumber,
                          final int columnNumber) {
        super(lineNumber, columnNumber);
    }

    protected CStatementTree(final StatementTreeBuilder<?> builder) {
        super(builder);
    }

}

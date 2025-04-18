package io.github.potjerodekool.nabu.compiler.tree.statement.impl;

import io.github.potjerodekool.nabu.compiler.tree.impl.CTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.StatementTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.builder.StatementTreeBuilder;

public abstract class CStatementTree extends CTree implements StatementTree {

    public CStatementTree(final int lineNumber,
                          final int columnNumber) {
        super(lineNumber, columnNumber);
    }

    public CStatementTree(final StatementTreeBuilder<? extends StatementTree, ? extends StatementTreeBuilder<?, ?>> builder) {
        super(builder);
    }

}

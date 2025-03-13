package io.github.potjerodekool.nabu.compiler.tree.statement.impl;

import io.github.potjerodekool.nabu.compiler.tree.impl.CTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.Statement;
import io.github.potjerodekool.nabu.compiler.tree.statement.StatementBuilder;

public abstract class CStatement extends CTree implements Statement {

    public CStatement(final int lineNumber,
                      final int charPositionInLine) {
        super(lineNumber, charPositionInLine);
    }

    public CStatement(final StatementBuilder<? extends Statement, ? extends StatementBuilder<?, ?>> builder) {
        super(builder);
    }

}

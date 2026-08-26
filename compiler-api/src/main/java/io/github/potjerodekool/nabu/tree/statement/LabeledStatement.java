package io.github.potjerodekool.nabu.tree.statement;

import io.github.potjerodekool.nabu.tree.statement.impl.CLabeledStatement;

/**
 * Labeled statement
 */
public interface LabeledStatement extends StatementTree {

    /**
     * @return Returns the label name.
     */
    String getLabel();

    /**
     * @return Returns the statements.
     */
    StatementTree getStatement();

    static LabeledStatement create(final String label,
                                   final StatementTree statement,
                                   final int lineNumber,
                                   final int columnNumber) {
        return new CLabeledStatement(label, statement, lineNumber, columnNumber);
    }
}

package io.github.potjerodekool.nabu.tree.statement;

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
}

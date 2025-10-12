package io.github.potjerodekool.nabu.tree.statement;

public interface LabeledStatement extends StatementTree {
    String getLabel();

    StatementTree getStatement();
}

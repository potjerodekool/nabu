package io.github.potjerodekool.nabu.compiler.tree.statement;

public interface LabeledStatement extends StatementTree {
    String getLabel();

    StatementTree getStatement();
}

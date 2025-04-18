package io.github.potjerodekool.nabu.compiler.tree.statement.impl;

import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.statement.LabeledStatement;
import io.github.potjerodekool.nabu.compiler.tree.statement.StatementTree;

public class CLabeledStatement extends CStatementTree implements LabeledStatement {

    private final String label;
    private final StatementTree statement;

    public CLabeledStatement(final String label,
                             final StatementTree statement,
                             final int lineNumber,
                             final int columnNumber) {
        super(lineNumber, columnNumber);
        this.label = label;
        this.statement = statement;
    }

    @Override
    public String getLabel() {
        return label;
    }

    @Override
    public StatementTree getStatement() {
        return statement;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitLabeledStatement(this, param);
    }
}

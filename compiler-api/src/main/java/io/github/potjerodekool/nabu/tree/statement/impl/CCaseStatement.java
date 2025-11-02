package io.github.potjerodekool.nabu.tree.statement.impl;

import io.github.potjerodekool.nabu.tree.CaseLabel;
import io.github.potjerodekool.nabu.tree.Tree;
import io.github.potjerodekool.nabu.tree.TreeVisitor;
import io.github.potjerodekool.nabu.tree.statement.CaseStatement;
import io.github.potjerodekool.nabu.tree.statement.builder.StatementTreeBuilder;

import java.util.List;

/**
 * Implementation of CaseStatement.
 */
public class CCaseStatement extends CStatementTree implements CaseStatement {

    private final CaseKind caseKind;
    private final List<CaseLabel> labels;
    private final Tree body;

    public CCaseStatement(final CaseKind caseKind,
                          final List<CaseLabel> labels,
                          final Tree body,
                          final int lineNumber,
                          final int columnNumber) {
        super(lineNumber, columnNumber);
        this.caseKind = caseKind;
        this.labels = labels;
        this.body = body;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitCaseStatement(this, param);
    }

    @Override
    public CaseKind getCaseKind() {
        return caseKind;
    }

    @Override
    public List<CaseLabel> getLabels() {
        return labels;
    }

    @Override
    public Tree getBody() {
        return body;
    }

    @Override
    public StatementTreeBuilder<?> builder() {
        return new StatementTreeBuilder(this);
    }
}

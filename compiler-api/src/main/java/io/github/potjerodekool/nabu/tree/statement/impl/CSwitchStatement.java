package io.github.potjerodekool.nabu.tree.statement.impl;

import io.github.potjerodekool.nabu.tree.TreeVisitor;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.statement.CaseStatement;
import io.github.potjerodekool.nabu.tree.statement.SwitchStatement;
import io.github.potjerodekool.nabu.tree.statement.builder.SwitchStatementBuilder;

import java.util.List;

public class CSwitchStatement extends CStatementTree implements SwitchStatement {

    private final ExpressionTree selector;
    private final List<CaseStatement> cases;

    public CSwitchStatement(final ExpressionTree selector,
                            final List<CaseStatement> cases,
                            final int lineNumber,
                            final int columnNumber) {
        super(lineNumber, columnNumber);
        this.selector = selector;
        this.cases = cases;
    }

    public CSwitchStatement(final SwitchStatementBuilder builder) {
        super(builder);
        this.selector = builder.getSelector();
        this.cases = builder.getCases();
    }

    @Override
    public ExpressionTree getSelector() {
        return selector;
    }

    @Override
    public List<CaseStatement> getCases() {
        return cases;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitSwitchStatement(this, param);
    }

    @Override
    public SwitchStatementBuilder builder() {
        return new SwitchStatementBuilder(this);
    }
}

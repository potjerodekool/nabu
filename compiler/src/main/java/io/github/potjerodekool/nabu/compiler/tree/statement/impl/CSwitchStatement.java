package io.github.potjerodekool.nabu.compiler.tree.statement.impl;

import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.CaseStatement;
import io.github.potjerodekool.nabu.compiler.tree.statement.SwitchStatement;

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
}

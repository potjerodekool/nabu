package io.github.potjerodekool.nabu.tree.expression.impl;

import io.github.potjerodekool.nabu.tree.TreeVisitor;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.SwitchExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.builder.SwitchExpressionTreeBuilder;
import io.github.potjerodekool.nabu.tree.statement.CaseStatement;

import java.util.List;

/**
 * Implementation of SwitchExpressionTree.
 */
public class CSwitchExpressionTree extends CExpressionTree implements SwitchExpressionTree {

    private final ExpressionTree selector;
    private final List<CaseStatement> cases;

    public CSwitchExpressionTree(final ExpressionTree selector,
                                 final List<CaseStatement> cases,
                                 final int lineNumber,
                                 final int columnNumber) {
        super(lineNumber, columnNumber);
        this.selector = selector;
        this.cases = cases;
    }

    public CSwitchExpressionTree(final SwitchExpressionTreeBuilder builder) {
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
        return visitor.visitSwitchExpression(this, param);
    }

    @Override
    public SwitchExpressionTreeBuilder builder() {
        return new SwitchExpressionTreeBuilder(this);
    }
}

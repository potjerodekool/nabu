package io.github.potjerodekool.nabu.tree.expression.builder;

import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.SwitchExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.impl.CSwitchExpressionTree;
import io.github.potjerodekool.nabu.tree.statement.CaseStatement;

import java.util.List;

/**
 * Builder for switch expressions.
 */
public class SwitchExpressionTreeBuilder extends ExpressionBuilder<SwitchExpressionTreeBuilder> {

    private ExpressionTree selector;
    private List<CaseStatement> cases;

    public SwitchExpressionTreeBuilder() {
    }

    public SwitchExpressionTreeBuilder(final SwitchExpressionTree switchExpressionTree) {
        super(switchExpressionTree);
        this.selector = switchExpressionTree.getSelector();
        this.cases = switchExpressionTree.getCases();
    }

    public ExpressionTree getSelector() {
        return selector;
    }

    public List<CaseStatement> getCases() {
        return cases;
    }

    @Override
    public SwitchExpressionTreeBuilder self() {
        return this;
    }

    public SwitchExpressionTreeBuilder selector(final ExpressionTree selector) {
        this.selector = selector;
        return this;
    }

    public SwitchExpressionTreeBuilder cases(final List<CaseStatement> cases) {
        this.cases = cases;
        return this;
    }

    @Override
    public SwitchExpressionTree build() {
        return new CSwitchExpressionTree(this);
    }
}

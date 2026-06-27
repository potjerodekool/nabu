package io.github.potjerodekool.nabu.tree.statement.builder;

import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.statement.CaseStatement;
import io.github.potjerodekool.nabu.tree.statement.SwitchStatement;
import io.github.potjerodekool.nabu.tree.statement.impl.CSwitchStatement;

import java.util.List;

/**
 * Builder for Switch statements.
 */
public class SwitchStatementBuilder extends StatementTreeBuilder<SwitchStatementBuilder> {

    private ExpressionTree selector;
    private List<CaseStatement> cases;

    public SwitchStatementBuilder() {
    }

    public SwitchStatementBuilder(final SwitchStatement switchStatement) {
        this.selector = switchStatement.getSelector();
        this.cases = switchStatement.getCases();
    }

    @Override
    public SwitchStatementBuilder self() {
        return this;
    }

    public ExpressionTree getSelector() {
        return selector;
    }

    public SwitchStatementBuilder selector(final ExpressionTree selector) {
        this.selector = selector;
        return this;
    }

    public List<CaseStatement> getCases() {
        return cases;
    }

    public SwitchStatementBuilder cases(final List<CaseStatement> cases) {
        this.cases = cases;
        return this;
    }

    @Override
    public SwitchStatement build() {
        return new CSwitchStatement(
                this
        );
    }
}

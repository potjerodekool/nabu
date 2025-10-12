package io.github.potjerodekool.nabu.tree.statement.builder;

import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.statement.ForStatementTree;
import io.github.potjerodekool.nabu.tree.statement.StatementTree;
import io.github.potjerodekool.nabu.tree.statement.impl.CForStatementTree;

import java.util.List;
import java.util.Objects;

public class ForStatementTreeBuilder extends StatementTreeBuilder<ForStatementTree, ForStatementTreeBuilder> {

    private List<StatementTree> forInit;
    private ExpressionTree expression;
    private List<StatementTree> forUpdate;
    private StatementTree statement;

    public ForStatementTreeBuilder(final ForStatementTree original) {
        super(original);
        this.forInit = original.getForInit();
        this.expression = original.getExpression();
        this.forUpdate = original.getForUpdate();
        this.statement = original.getStatement();
    }

    public List<StatementTree> getForInit() {
        return Objects.requireNonNullElseGet(forInit, List::of);
    }

    public ExpressionTree getExpression() {
        return expression;
    }

    public List<StatementTree> getForUpdate() {
        return Objects.requireNonNullElseGet(forUpdate, List::of);
    }

    public StatementTree getStatement() {
        return statement;
    }

    @Override
    public ForStatementTreeBuilder self() {
        return null;
    }

    @Override
    public ForStatementTree build() {
        return new CForStatementTree(this);
    }

    public ForStatementTreeBuilder forInit(final List<StatementTree> forInit) {
        this.forInit = List.copyOf(forInit);
        return this;
    }

    public ForStatementTreeBuilder expression(final ExpressionTree expression) {
        this.expression = expression;
        return this;
    }

    public ForStatementTreeBuilder forUpdate(final List<StatementTree> forUpdate) {
        this.forUpdate = List.copyOf(forUpdate);
        return this;
    }

    public ForStatementTreeBuilder statement(final StatementTree statement) {
        this.statement = statement;
        return this;
    }
}

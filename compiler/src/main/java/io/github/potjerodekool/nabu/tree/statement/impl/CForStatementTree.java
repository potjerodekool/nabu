package io.github.potjerodekool.nabu.tree.statement.impl;

import io.github.potjerodekool.nabu.tree.TreeVisitor;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.statement.ForStatementTree;
import io.github.potjerodekool.nabu.tree.statement.StatementTree;
import io.github.potjerodekool.nabu.tree.statement.builder.ForStatementTreeBuilder;

import java.util.List;

/**
 * Implementation of ForStatement.
 */
public class CForStatementTree extends CStatementTree implements ForStatementTree {

    private final List<StatementTree> forInit;
    private final ExpressionTree expression;
    private final List<StatementTree> forUpdate;
    private final StatementTree statement;

    public CForStatementTree(final List<StatementTree> forInit,
                             final ExpressionTree expression,
                             final List<StatementTree> forUpdate,
                             final StatementTree statement,
                             final int lineNumber,
                             final int columnNumber) {
        super(lineNumber, columnNumber);
        this.forInit = forInit;
        this.expression = expression;
        this.forUpdate = forUpdate;
        this.statement = statement;
    }

    public CForStatementTree(final ForStatementTreeBuilder builder) {
        super(builder);
        this.forInit = builder.getForInit();
        this.expression = builder.getExpression();
        this.forUpdate = builder.getForUpdate();
        this.statement = builder.getStatement();
    }

    public List<StatementTree> getForInit() {
        return forInit;
    }

    public ExpressionTree getCondition() {
        return expression;
    }

    public List<StatementTree> getForUpdate() {
        return forUpdate;
    }

    public StatementTree getStatement() {
        return statement;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitForStatement(this, param);
    }

    @Override
    public ForStatementTreeBuilder builder() {
        return new ForStatementTreeBuilder(this);
    }

}

package io.github.potjerodekool.nabu.tree.statement.impl;

import io.github.potjerodekool.nabu.tree.Tree;
import io.github.potjerodekool.nabu.tree.TreeVisitor;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.statement.ExpressionStatementTree;
import io.github.potjerodekool.nabu.tree.statement.builder.StatementExpressionTreeBuilder;

import java.util.List;
import java.util.Objects;

/**
 * Implementation of ExpressionStatement.
 */
public class CExpressionStatementTree extends CStatementTree implements ExpressionStatementTree {

    final ExpressionTree expression;

    public CExpressionStatementTree(final ExpressionTree expression) {
        this(expression, -1, -1);
    }

    public CExpressionStatementTree(final ExpressionTree expression,
                                    final int line,
                                    final int columnNumber) {
        super(line, columnNumber);
        Objects.requireNonNull(expression, "expression is null");
        this.expression = expression;
    }

    public CExpressionStatementTree(final StatementExpressionTreeBuilder builder) {
        super(builder);
        this.expression = builder.getExpression();
        Objects.requireNonNull(expression, "expression is null");
    }

    public ExpressionTree getExpression() {
        return expression;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitExpressionStatement(this, param);
    }

    @Override
    public StatementExpressionTreeBuilder builder() {
        return new StatementExpressionTreeBuilder(this);
    }

    @Override
    public String toString() {
        return expression.toString() + ";";
    }

    @Override
    public List<? extends Tree> children() {
        return List.of(expression);
    }
}

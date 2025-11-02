package io.github.potjerodekool.nabu.tree.statement.impl;

import io.github.potjerodekool.nabu.tree.TreeVisitor;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.statement.ReturnStatementTree;
import io.github.potjerodekool.nabu.tree.statement.builder.ReturnStatementTreeBuilder;

import java.util.Objects;

/**
 * Implementation of ReturnStatement.
 */
public class CReturnStatementTree extends CStatementTree implements ReturnStatementTree {

    private ExpressionTree expression;

    public CReturnStatementTree() {
        this(null, -1, -1);
    }

    public CReturnStatementTree(final ExpressionTree expression) {
        this(expression, -1, -1);
    }

    public CReturnStatementTree(final ExpressionTree expression,
                                final int lineNumber,
                                final int columnNumber) {
        super(lineNumber, columnNumber);
        this.expression = expression;
    }

    public CReturnStatementTree(final ReturnStatementTreeBuilder builder) {
        super(builder);
        this.expression = builder.getExpression();
    }

    public ExpressionTree getExpression() {
        return expression;
    }

    public CReturnStatementTree expression(final ExpressionTree expression) {
        Objects.requireNonNull(expression);
        this.expression = expression;
        return this;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitReturnStatement(this, param);
    }

    @Override
    public ReturnStatementTreeBuilder builder() {
        return new ReturnStatementTreeBuilder(this);
    }

    @Override
    public String toString() {
        if (expression == null) {
            return "return;";
        } else {
            return "return " + expression + ";";
        }
    }
}

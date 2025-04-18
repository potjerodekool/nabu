package io.github.potjerodekool.nabu.compiler.tree.statement.impl;

import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.IfStatementTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.builder.IfStatementTreeBuilder;
import io.github.potjerodekool.nabu.compiler.tree.statement.StatementTree;

public class CIfStatementTree extends CStatementTree implements IfStatementTree {

    private final ExpressionTree expression;
    private final StatementTree thenStatement;
    private final StatementTree elseStatement;

    public CIfStatementTree(final ExpressionTree expression,
                            final StatementTree thenStatement,
                            final StatementTree elseStatement,
                            final int lineNumber,
                            final int columnNumber) {
        super(lineNumber, columnNumber);
        this.expression = expression;
        this.thenStatement = thenStatement;
        this.elseStatement = elseStatement;
    }

    public CIfStatementTree(final IfStatementTreeBuilder builder) {
        super(builder);
        this.expression = builder.getExpression();
        this.thenStatement = builder.getThenStatement();
        this.elseStatement = builder.getElseStatement();
    }

    public ExpressionTree getExpression() {
        return expression;
    }

    public StatementTree getThenStatement() {
        return thenStatement;
    }

    public StatementTree getElseStatement() {
        return elseStatement;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitIfStatement(this, param);
    }

    @Override
    public IfStatementTreeBuilder builder() {
        return new IfStatementTreeBuilder(this);
    }

}

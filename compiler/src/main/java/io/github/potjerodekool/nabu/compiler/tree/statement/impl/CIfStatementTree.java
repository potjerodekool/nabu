package io.github.potjerodekool.nabu.compiler.tree.statement.impl;

import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.IfStatementTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.IfStatementTreeBuilder;
import io.github.potjerodekool.nabu.compiler.tree.statement.Statement;

public class CIfStatementTree extends CStatement implements IfStatementTree {

    private final ExpressionTree expression;
    private final Statement thenStatement;
    private final Statement elseStatement;

    public CIfStatementTree(final ExpressionTree expression,
                            final Statement thenStatement,
                            final Statement elseStatement,
                            final int lineNumber,
                            final int charPositionInLine) {
        super(lineNumber, charPositionInLine);
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

    public Statement getThenStatement() {
        return thenStatement;
    }

    public Statement getElseStatement() {
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

package io.github.potjerodekool.nabu.compiler.tree.statement;

import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;

public class IfStatementTree extends Statement {

    private final ExpressionTree expression;
    private final Statement thenStatement;
    private final Statement elseStatement;

    public IfStatementTree(final ExpressionTree expression,
                           final Statement thenStatement,
                           final Statement elseStatement) {
        this.expression = expression;
        this.thenStatement = thenStatement;
        this.elseStatement = elseStatement;
    }

    public IfStatementTree(final IfStatementTreeBuilder builder) {
        super(builder);
        this.expression = builder.expression;
        this.thenStatement = builder.thenStatement;
        this.elseStatement = builder.elseStatement;
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

    public IfStatementTreeBuilder builder() {
        return new IfStatementTreeBuilder(this);
    }

}

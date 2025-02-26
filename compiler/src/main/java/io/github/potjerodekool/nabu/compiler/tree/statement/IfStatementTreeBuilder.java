package io.github.potjerodekool.nabu.compiler.tree.statement;

import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;

public class IfStatementTreeBuilder extends StatementBuilder<IfStatementTree> {

    ExpressionTree expression;
    Statement thenStatement;
    Statement elseStatement;

    protected IfStatementTreeBuilder(final IfStatementTree original) {
        super(original);
        expression = original.getExpression();
        this.thenStatement = original.getThenStatement();
        this.elseStatement = original.getElseStatement();
    }

    @Override
    public IfStatementTreeBuilder self() {
        return this;
    }

    public IfStatementTreeBuilder expression(final ExpressionTree expression) {
        this.expression = expression;
        return this;
    }

    public IfStatementTreeBuilder thenStatement(final Statement thenStatement) {
        this.thenStatement = thenStatement;
        return this;
    }

    public IfStatementTreeBuilder elseStatement(final Statement elseStatement) {
        this.elseStatement = elseStatement;
        return this;
    }

    @Override
    public IfStatementTree build() {
        return new IfStatementTree(this);
    }
}

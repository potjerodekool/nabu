package io.github.potjerodekool.nabu.compiler.tree.statement;

import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.impl.CIfStatementTree;

public class IfStatementTreeBuilder extends StatementBuilder<IfStatementTree, IfStatementTreeBuilder> {

    private ExpressionTree expression;
    private Statement thenStatement;
    private Statement elseStatement;

    public IfStatementTreeBuilder(final IfStatementTree original) {
        super(original);
        expression = original.getExpression();
        this.thenStatement = original.getThenStatement();
        this.elseStatement = original.getElseStatement();
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
        return new CIfStatementTree(this);
    }
}

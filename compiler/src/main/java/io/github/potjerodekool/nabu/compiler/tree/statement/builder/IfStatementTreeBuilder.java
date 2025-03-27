package io.github.potjerodekool.nabu.compiler.tree.statement.builder;

import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.IfStatementTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.StatementTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.impl.CIfStatementTree;

public class IfStatementTreeBuilder extends StatementTreeBuilder<IfStatementTree, IfStatementTreeBuilder> {

    private ExpressionTree expression;
    private StatementTree thenStatement;
    private StatementTree elseStatement;

    public IfStatementTreeBuilder(final IfStatementTree original) {
        super(original);
        expression = original.getExpression();
        this.thenStatement = original.getThenStatement();
        this.elseStatement = original.getElseStatement();
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
    public IfStatementTreeBuilder self() {
        return this;
    }

    public IfStatementTreeBuilder expression(final ExpressionTree expression) {
        this.expression = expression;
        return this;
    }

    public IfStatementTreeBuilder thenStatement(final StatementTree thenStatement) {
        this.thenStatement = thenStatement;
        return this;
    }

    public IfStatementTreeBuilder elseStatement(final StatementTree elseStatement) {
        this.elseStatement = elseStatement;
        return this;
    }

    @Override
    public IfStatementTree build() {
        return new CIfStatementTree(this);
    }
}

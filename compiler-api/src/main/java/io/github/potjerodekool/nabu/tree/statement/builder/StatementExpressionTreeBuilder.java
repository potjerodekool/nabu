package io.github.potjerodekool.nabu.tree.statement.builder;


import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.statement.ExpressionStatementTree;
import io.github.potjerodekool.nabu.tree.statement.impl.CExpressionStatementTree;

public class StatementExpressionTreeBuilder extends StatementTreeBuilder<ExpressionStatementTree, StatementExpressionTreeBuilder> {

    private ExpressionTree expression;

    public StatementExpressionTreeBuilder(final ExpressionStatementTree expressionStatement) {
        super(expressionStatement);
        this.expression = expressionStatement.getExpression();
    }

    @Override
    public StatementExpressionTreeBuilder self() {
        return this;
    }

    public StatementExpressionTreeBuilder expression(final ExpressionTree expression) {
        this.expression = expression;
        return this;
    }

    public ExpressionTree getExpression() {
        return expression;
    }

    public ExpressionStatementTree build() {
        return new CExpressionStatementTree(this);
    }

}

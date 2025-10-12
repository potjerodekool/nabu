package io.github.potjerodekool.nabu.tree.statement.builder;


import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.statement.ReturnStatementTree;
import io.github.potjerodekool.nabu.tree.statement.impl.CReturnStatementTree;

public class ReturnStatementTreeBuilder extends StatementTreeBuilder<ReturnStatementTree, ReturnStatementTreeBuilder> {

    private ExpressionTree expression;

    public ReturnStatementTreeBuilder() {
        super();
    }


    public ReturnStatementTreeBuilder(final ReturnStatementTree original) {
        super(original);
        this.expression = original.getExpression();
    }

    public ExpressionTree getExpression() {
        return expression;
    }

    @Override
    public ReturnStatementTreeBuilder self() {
        return this;
    }

    public ReturnStatementTreeBuilder expression(final ExpressionTree expression) {
        this.expression = expression;
        return this;
    }

    @Override
    public ReturnStatementTree build() {
        return new CReturnStatementTree(this);
    }
}

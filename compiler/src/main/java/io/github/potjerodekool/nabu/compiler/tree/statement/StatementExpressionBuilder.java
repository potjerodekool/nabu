package io.github.potjerodekool.nabu.compiler.tree.statement;

import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.impl.CExpressionStatement;

public class StatementExpressionBuilder extends StatementBuilder<ExpressionStatement, StatementExpressionBuilder> {

    private ExpressionTree expression;

    public StatementExpressionBuilder(final ExpressionStatement expressionStatement) {
        super(expressionStatement);
        this.expression = expressionStatement.getExpression();
    }

    @Override
    public StatementExpressionBuilder self() {
        return this;
    }

    public StatementExpressionBuilder expression(final ExpressionTree expression) {
        this.expression = expression;
        return this;
    }

    public ExpressionTree getExpression() {
        return expression;
    }

    public ExpressionStatement build() {
        return new CExpressionStatement(this);
    }

}

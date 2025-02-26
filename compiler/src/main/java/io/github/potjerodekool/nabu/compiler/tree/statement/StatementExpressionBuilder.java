package io.github.potjerodekool.nabu.compiler.tree.statement;

import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;

public class StatementExpressionBuilder extends StatementBuilder<StatementExpression> {

    ExpressionTree expression;

    public StatementExpressionBuilder(final StatementExpression statementExpression) {
        super(statementExpression);
        this.expression = statementExpression.expression;
    }

    @Override
    public StatementExpressionBuilder self() {
        return this;
    }

    public StatementExpressionBuilder expression(final ExpressionTree expression) {
        this.expression = expression;
        return this;
    }

    public StatementExpression build() {
        return new StatementExpression(this);
    }

}

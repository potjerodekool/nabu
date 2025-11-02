package io.github.potjerodekool.nabu.tree.statement.builder;

import io.github.potjerodekool.nabu.tree.Tree;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.statement.YieldStatement;
import io.github.potjerodekool.nabu.tree.statement.impl.CYieldStatement;

/**
 * Builder for yield statements.
 */
public class YieldStatementBuilder extends StatementTreeBuilder<YieldStatementBuilder> {

    private ExpressionTree expression;

    public YieldStatementBuilder(final YieldStatement statement) {
        this.expression = statement.getExpression();
    }

    public ExpressionTree getExpression() {
        return expression;
    }

    public YieldStatementBuilder expression(final ExpressionTree expression) {
        this.expression = expression;
        return this;
    }


    @Override
    public YieldStatementBuilder self() {
        return this;
    }

    @Override
    public Tree build() {
        return new CYieldStatement(this);
    }
}

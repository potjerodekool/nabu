package io.github.potjerodekool.nabu.compiler.tree.statement.impl;

import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.ExpressionStatement;
import io.github.potjerodekool.nabu.compiler.tree.statement.StatementExpressionBuilder;

public class CExpressionStatement extends CStatement implements ExpressionStatement {

    final ExpressionTree expression;

    public CExpressionStatement(final ExpressionTree expression,
                                final int line,
                                final int charPositionInLine) {
        super(line, charPositionInLine);
        this.expression = expression;
    }

    public CExpressionStatement(final StatementExpressionBuilder builder) {
        super(builder);
        this.expression = builder.getExpression();
    }

    public ExpressionTree getExpression() {
        return expression;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visiExpressionStatement(this, param);
    }

    @Override
    public StatementExpressionBuilder builder() {
        return new StatementExpressionBuilder(this);
    }

}

package io.github.potjerodekool.nabu.compiler.tree.statement.impl;

import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.ExpressionStatementTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.builder.StatementExpressionTreeBuilder;

public class CExpressionStatementTree extends CStatementTree implements ExpressionStatementTree {

    final ExpressionTree expression;

    public CExpressionStatementTree(final ExpressionTree expression,
                                    final int line,
                                    final int charPositionInLine) {
        super(line, charPositionInLine);
        this.expression = expression;
    }

    public CExpressionStatementTree(final StatementExpressionTreeBuilder builder) {
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
    public StatementExpressionTreeBuilder builder() {
        return new StatementExpressionTreeBuilder(this);
    }

}

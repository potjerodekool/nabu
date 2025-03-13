package io.github.potjerodekool.nabu.compiler.tree.expression.impl;

import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.expression.ArrayAccessExpression;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.builder.ArrayAccessExpressionBuilder;

public class CArrayAccessExpression extends CExpressionTree implements ArrayAccessExpression {

    private final ExpressionTree expression;
    private final ExpressionTree index;

    public CArrayAccessExpression(final ExpressionTree expression,
                                  final ExpressionTree index,
                                  final int lineNumber,
                                  final int columnNumber) {
        super(lineNumber, columnNumber);
        this.expression = expression;
        this.index = index;
    }

    public CArrayAccessExpression(final ArrayAccessExpressionBuilder arrayAccessExpressionBuilder) {
        super(arrayAccessExpressionBuilder);
        this.expression = arrayAccessExpressionBuilder.getExpression();
        this.index = arrayAccessExpressionBuilder.getIndex();
    }

    @Override
    public ExpressionTree getExpression() {
        return expression;
    }

    @Override
    public ExpressionTree getIndex() {
        return index;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitArrayAccess(this, param);
    }

    @Override
    public ArrayAccessExpressionBuilder builder() {
        return new ArrayAccessExpressionBuilder(this);
    }
}

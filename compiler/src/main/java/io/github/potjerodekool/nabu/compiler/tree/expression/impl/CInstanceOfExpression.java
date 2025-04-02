package io.github.potjerodekool.nabu.compiler.tree.expression.impl;

import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.InstanceOfExpression;

public class CInstanceOfExpression extends CExpressionTree implements InstanceOfExpression {

    private final ExpressionTree expression;
    private final ExpressionTree typeExpression;

    public CInstanceOfExpression(final ExpressionTree expression,
                                 final ExpressionTree typeExpression,
                                 final int lineNumber,
                                 final int charPositionInLine) {
        super(lineNumber, charPositionInLine);
        this.expression = expression;
        this.typeExpression = typeExpression;
    }

    @Override
    public ExpressionTree getExpression() {
        return expression;
    }

    @Override
    public ExpressionTree getTypeExpression() {
        return typeExpression;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitInstanceOfExpression(this, param);
    }

}

package io.github.potjerodekool.nabu.tree.expression.impl;

import io.github.potjerodekool.nabu.tree.TreeVisitor;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.ParenthesizedExpression;
import io.github.potjerodekool.nabu.type.TypeMirror;

public class CParenthesizedExpressionTree extends CExpressionTree implements ParenthesizedExpression {

    private final ExpressionTree expressionTree;

    public CParenthesizedExpressionTree(final ExpressionTree expressionTree,
                                        final int lineNumber,
                                        final int columnNumber) {
        super(lineNumber, columnNumber);
        this.expressionTree = expressionTree;
    }

    @Override
    public ExpressionTree getExpression() {
        return expressionTree;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitParenthesizedExpression(this, param);
    }

    @Override
    public String toString() {
        return "(%s)".formatted(expressionTree);
    }

    @Override
    public TypeMirror getType() {
        return expressionTree.getType();
    }
}

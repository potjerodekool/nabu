package io.github.potjerodekool.nabu.tree.impl;

import io.github.potjerodekool.nabu.tree.ConstantCaseLabel;
import io.github.potjerodekool.nabu.tree.TreeVisitor;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;

public class CConstantCaseLabel extends CCaseLabel implements ConstantCaseLabel {

    private final ExpressionTree expression;

    public CConstantCaseLabel(final ExpressionTree expression,
                              final int lineNumber,
                              final int columnNumber) {
        super(lineNumber, columnNumber);
        this.expression = expression;
    }

    @Override
    public ExpressionTree getExpression() {
        return expression;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitConstantCaseLabel(this, param);
    }
}

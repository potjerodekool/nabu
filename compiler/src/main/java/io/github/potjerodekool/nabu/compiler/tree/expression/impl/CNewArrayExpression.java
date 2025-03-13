package io.github.potjerodekool.nabu.compiler.tree.expression.impl;

import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.NewArrayExpression;

import java.util.List;

public class CNewArrayExpression extends CExpressionTree implements NewArrayExpression {

    private final List<ExpressionTree> elements;

    public CNewArrayExpression(final List<ExpressionTree> elements,
                               final int lineNumber,
                               final int charPositionInLine) {
        super(lineNumber, charPositionInLine);
        this.elements = elements;
    }

    public List<ExpressionTree> getElements() {
        return elements;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitNewArray(this, param);
    }
}

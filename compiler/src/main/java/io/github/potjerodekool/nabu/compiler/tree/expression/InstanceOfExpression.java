package io.github.potjerodekool.nabu.compiler.tree.expression;

import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;

public class InstanceOfExpression extends ExpressionTree {

    private final ExpressionTree expression;
    private final ExpressionTree typeExpression;

    public InstanceOfExpression(final ExpressionTree expression,
                                final ExpressionTree typeExpression) {
        this.expression = expression;
        this.typeExpression = typeExpression;
    }

    public ExpressionTree getExpression() {
        return expression;
    }

    public ExpressionTree getTypeExpression() {
        return typeExpression;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitInstanceOfExpression(this, param);
    }
}

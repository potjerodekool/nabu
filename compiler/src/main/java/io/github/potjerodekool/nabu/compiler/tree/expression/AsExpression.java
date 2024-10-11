package io.github.potjerodekool.nabu.compiler.tree.expression;

import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;

public class AsExpression extends CExpression {

    private CExpression expression;

    private CExpression targetType;

    public CExpression getExpression() {
        return expression;
    }

    public void setExpression(final CExpression expression) {
        this.expression = expression;
    }

    public CExpression getTargetType() {
        return targetType;
    }

    public void setTargetType(final CExpression targetType) {
        this.targetType = targetType;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitAsExpression(this, param);
    }
}

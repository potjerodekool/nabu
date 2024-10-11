package io.github.potjerodekool.nabu.compiler.tree.expression;

import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;

public class CFieldAccessExpression extends CExpression {

    private CExpression target;

    private CExpression field;

    public CExpression getTarget() {
        return target;
    }

    public void setTarget(final CExpression target) {
        this.target = target;
    }

    public CExpression getField() {
        return field;
    }

    public void setField(final CExpression field) {
        this.field = field;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitFieldAccessExpression(this, param);
    }

    @Override
    public String toString() {
        if (target == null) {
            return field.toString();
        } else {
            final var targetStr = target.toString();
            final var fieldStr = field.toString();
            return String.format("%s.%s", targetStr, fieldStr);
        }
    }
}

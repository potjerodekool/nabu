package io.github.potjerodekool.nabu.compiler.tree.expression;

import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;

import java.util.Objects;

public class FieldAccessExpressioTree extends ExpressionTree {

    private ExpressionTree target;

    private ExpressionTree field;

    public FieldAccessExpressioTree(final ExpressionTree target,
                                    final ExpressionTree field) {
        Objects.requireNonNull(target);
        Objects.requireNonNull(field);
        this.target = target;
        this.field = field;
    }

    public ExpressionTree getTarget() {
        return target;
    }

    public FieldAccessExpressioTree target(final ExpressionTree target) {
        this.target = target;
        return this;
    }

    public ExpressionTree getField() {
        return field;
    }

    public FieldAccessExpressioTree field(final ExpressionTree field) {
        this.field = field;
        return this;
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

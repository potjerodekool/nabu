package io.github.potjerodekool.nabu.compiler.tree.expression;

import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;

public class CNoTypeExpression extends CExpression {

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitNoTypeExpression(this, param);
    }
}

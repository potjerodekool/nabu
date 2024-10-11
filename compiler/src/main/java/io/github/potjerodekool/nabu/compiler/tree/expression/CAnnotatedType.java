package io.github.potjerodekool.nabu.compiler.tree.expression;

import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;

import java.util.List;

public class CAnnotatedType extends CExpression {

    private final CExpression clazz;

    private final List<CExpression> arguments;

    public CAnnotatedType(final CExpression clazz, final List<CExpression> arguments) {
        this.clazz = clazz;
        this.arguments = arguments;
    }

    public CExpression getClazz() {
        return clazz;
    }

    public List<CExpression> getArguments() {
        return arguments;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitAnnotatedType(this, param);
    }
}

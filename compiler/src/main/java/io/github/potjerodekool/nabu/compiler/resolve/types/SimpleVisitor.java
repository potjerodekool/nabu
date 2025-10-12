package io.github.potjerodekool.nabu.compiler.resolve.types;


import io.github.potjerodekool.nabu.type.CapturedType;

public abstract class SimpleVisitor<R, P> extends AbstractTypeVisitor<R,P> {

    @Override
    public R visitCapturedType(final CapturedType capturedType, final P param) {
        return visitTypeVariable(capturedType, param);
    }
}

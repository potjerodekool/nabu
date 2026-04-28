package io.github.potjerodekool.nabu.compiler.resolve.types;

import io.github.potjerodekool.nabu.type.TypeMirror;

public abstract class UnaryVisitor<R> extends AbstractTypeVisitor<R, Void> {

    public R visit(final TypeMirror type) {
        return type != null ? type.accept(this, null) : null;
    }
}

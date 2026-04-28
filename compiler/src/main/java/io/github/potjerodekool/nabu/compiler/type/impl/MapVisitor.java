package io.github.potjerodekool.nabu.compiler.type.impl;

import io.github.potjerodekool.nabu.compiler.resolve.types.AbstractTypeVisitor;
import io.github.potjerodekool.nabu.type.TypeMirror;

public class MapVisitor<S> extends AbstractTypeVisitor<AbstractType, S> {

    AbstractType visit(AbstractType type) {
        return type.accept(this, null);
    }

    @Override
    public AbstractType visitUnknownType(final TypeMirror typeMirror, final S param) {
        return (AbstractType) typeMirror;
    }
}

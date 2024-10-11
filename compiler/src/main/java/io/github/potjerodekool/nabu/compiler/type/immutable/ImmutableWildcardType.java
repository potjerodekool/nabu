package io.github.potjerodekool.nabu.compiler.type.immutable;

import io.github.potjerodekool.nabu.compiler.type.*;

public class ImmutableWildcardType extends Type implements WildcardType {

    private final TypeMirror extendsBound;
    private final TypeMirror superBound;

    public ImmutableWildcardType() {
        this(null, null);
    }

    public ImmutableWildcardType(final TypeMirror extendsBound,
                                 final TypeMirror superBound) {
        this.extendsBound = extendsBound;
        this.superBound = superBound;
    }

    @Override
    public TypeMirror getExtendsBound() {
        return extendsBound;
    }

    @Override
    public TypeMirror getSuperBound() {
        return superBound;
    }

    @Override
    public TypeKind getKind() {
        return TypeKind.WILDCARD;
    }

    @Override
    public <R, P> R accept(final TypeVisitor<R, P> visitor, final P param) {
        return visitor.visitWildcardType(this, param);
    }
}

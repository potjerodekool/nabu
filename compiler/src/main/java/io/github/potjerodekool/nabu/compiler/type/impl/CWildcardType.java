package io.github.potjerodekool.nabu.compiler.type.impl;

import io.github.potjerodekool.nabu.compiler.type.*;

public class CWildcardType extends AbstractType implements WildcardType {

    private final TypeMirror bound;
    private final BoundKind boundKind;

    public CWildcardType(final TypeMirror extendsBound,
                         final TypeMirror superBound) {
        if (extendsBound != null) {
            this.bound = extendsBound;
            this.boundKind = BoundKind.EXTENDS;
        } else if (superBound != null) {
            this.bound = superBound;
            this.boundKind = BoundKind.SUPER;
        } else {
            this.bound = null;
            this.boundKind = BoundKind.UNBOUND;
        }
    }

    @Override
    public TypeMirror getExtendsBound() {
        return BoundKind.EXTENDS == boundKind
                ? bound
                : null;
    }

    @Override
    public TypeMirror getSuperBound() {
        return BoundKind.SUPER == boundKind
                ? bound
                : null;
    }

    @Override
    public TypeMirror getBound() {
        return bound;
    }

    @Override
    public BoundKind getBoundKind() {
        return boundKind;
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

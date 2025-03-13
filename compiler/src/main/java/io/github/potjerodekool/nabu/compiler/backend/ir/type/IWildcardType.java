package io.github.potjerodekool.nabu.compiler.backend.ir.type;

import io.github.potjerodekool.nabu.compiler.type.BoundKind;

public final class IWildcardType extends IType {

    private final BoundKind boundKind;
    private final IType bound;

    public IWildcardType(final BoundKind boundKind,
                         final IType bound) {
        super(ITypeKind.WILDCARD);
        this.boundKind = boundKind;
        this.bound = bound;
    }

    public IType getBound() {
        return bound;
    }

    public BoundKind getBoundKind() {
        return boundKind;
    }

    @Override
    public <R, P> R accept(final ITypeVisitor<R, P> visitor, final P param) {
        return visitor.visitWildcardType(this, param);
    }
}

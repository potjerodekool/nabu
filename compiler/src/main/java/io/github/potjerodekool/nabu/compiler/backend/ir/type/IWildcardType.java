package io.github.potjerodekool.nabu.compiler.backend.ir.type;

public final class IWildcardType extends IType {

    private final IType extendsBound;
    private final IType superBound;

    public IWildcardType(final IType extendsBound,
                         final IType superBound) {
        super(ITypeKind.WILDCARD);
        this.extendsBound = extendsBound;
        this.superBound = superBound;
    }

    public IType getExtendsBound() {
        return extendsBound;
    }

    public IType getSuperBound() {
        return superBound;
    }

    @Override
    public <R, P> R accept(final ITypeVisitor<R, P> visitor, final P param) {
        return visitor.visitWildcardType(this, param);
    }
}

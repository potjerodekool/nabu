package io.github.potjerodekool.nabu.compiler.backend.ir.type;

public final class IArrayType extends IType {

    private final IType componentType;

    public IArrayType(final IType componentType) {
        super(ITypeKind.ARRAY);
        this.componentType = componentType;
    }

    public IType getComponentType() {
        return componentType;
    }

    @Override
    public <R, P> R accept(final ITypeVisitor<R, P> visitor, final P param) {
        return visitor.visitArrayType(this, param);
    }

    @Override
    public boolean equals(final Object o) {
        return o instanceof IArrayType otherArrayType
                && componentType.equals(otherArrayType.componentType);
    }

    @Override
    public int hashCode() {
        return componentType.hashCode();
    }
}

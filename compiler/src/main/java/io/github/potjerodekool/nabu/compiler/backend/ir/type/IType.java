package io.github.potjerodekool.nabu.compiler.backend.ir.type;

import io.github.potjerodekool.nabu.compiler.backend.ir.Constants;

import java.util.List;

public sealed abstract class IType permits IIntersectionType, IPrimitiveType, IReferenceType, ITypeVariable, IWildcardType {

    private final ITypeKind kind;

    IType(final ITypeKind kind) {
        this.kind = kind;
    }

    public final ITypeKind getKind() {
        return kind;
    }

    @Override
    public String toString() {
        return String.valueOf(kind);
    }

    public boolean isGeneric() {
        return false;
    }

    public boolean isVarArg() {
        return false;
    }

    public boolean isEnumType() {
        return false;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        } else if (!(o instanceof IType)) {
            return false;
        }
        final IType othterType = (IType) o;
        return kind == othterType.kind;

    }

    @Override
    public int hashCode() {
        return 0;
    }

    public static IType getType(final Object value) {
        IType type = IPrimitiveType.getType(value);

        if (type != null) {
            return type;
        } else if (value instanceof String) {
            return IReferenceType.createClassType(null, Constants.STRING, List.of());
        } else if (value instanceof IReferenceType) {
            //TODO class literal ???
            return (IReferenceType) value;
        } else if (value == null) {
            return IReferenceType.NULL;
        } else {
            throw new IllegalArgumentException("" + value);
        }
    }

    public abstract <R, P> R accept(final ITypeVisitor<R, P> visitor,
                                    final P param);
}

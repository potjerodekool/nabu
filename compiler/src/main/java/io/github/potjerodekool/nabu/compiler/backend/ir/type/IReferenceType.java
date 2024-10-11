package io.github.potjerodekool.nabu.compiler.backend.ir.type;

import java.util.List;
import java.util.Objects;

public final class IReferenceType extends IType {

    public static final IType NULL = new IReferenceType(
            null,
            ITypeKind.NULL,
            null);

    private final String name;
    private final List<IType> typeArguments;

    IReferenceType(final String name,
                   final ITypeKind typeKind,
                   final List<IType> typeArguments) {
        super(typeKind);

        if (name == null && ITypeKind.NULL != typeKind) {
            throw new NullPointerException();
        }

        if (typeArguments != null) {
            if (typeArguments.isEmpty()) {
                throw new IllegalArgumentException();
            }

            for (final IType typeArgument : typeArguments) {
                if (typeArgument == null) {
                    throw new NullPointerException();
                }
            }

            for (final IType typeArgument : typeArguments) {
                if (typeArgument.getKind() == ITypeKind.CHAR) {
                    throw new IllegalArgumentException();
                }
            }
        }

        this.name = name;
        this.typeArguments = typeArguments;
    }

    public static IReferenceType create(final String name,
                                        final List<IType> typeArguments) {
        return new IReferenceType(name, ITypeKind.DECLARED, typeArguments);
    }

    public static IReferenceType create(final String name) {
        return new IReferenceType(name, ITypeKind.DECLARED, null);
    }

    public String getName() {
        return name;
    }

    public List<IType> getTypeArguments() {
        return typeArguments;
    }

    @Override
    public boolean isGeneric() {
        return !typeArguments.isEmpty();
    }

    @Override
    public boolean isEnumType() {
        return getKind() == ITypeKind.ENUM;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        } else if (!(o instanceof IReferenceType)) {
            return false;
        }
        final IReferenceType that = (IReferenceType) o;
        return getKind() != that.getKind()
                || Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        result = 31 * result + (name != null ? name.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return name;
    }
}

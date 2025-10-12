package io.github.potjerodekool.nabu.compiler.backend.ir.type;

import io.github.potjerodekool.nabu.util.CollectionUtils;

import java.util.List;
import java.util.Objects;

public final class IReferenceType extends IType {

    public static final IType NULL = new IReferenceType(
            ITypeKind.NULL, null,
            null,
            null);

    private final IReferenceType enclosingType;
    private final String name;
    private final List<IType> typeArguments;

    public static IReferenceType createClassType(final String name) {
        return createClassType(null,name, List.of());
    }

    public static IReferenceType createClassType(final IReferenceType enclosingType,
                                                 final String name,
                                                 final List<IType> typeArguments) {
        return new IReferenceType(
                ITypeKind.CLASS,
                enclosingType,
                name,
                typeArguments
        );
    }

    public static IReferenceType createInterfaceType(final IReferenceType enclosingType,
                                                     final String name,
                                                     final List<IType> typeArguments) {
        return new IReferenceType(
                ITypeKind.INTERFACE,
                enclosingType,
                name,
                typeArguments
        );
    }

    private IReferenceType(final ITypeKind typeKind,
                           final IReferenceType enclosingType,
                           final String name,
                           final List<IType> typeArguments) {
        super(typeKind);
        this.enclosingType = enclosingType;

        if (typeArguments != null) {
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

    public String getName() {
        return name;
    }

    public IReferenceType getEnclosingType() {
        return enclosingType;
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

        return getKind() == that.getKind()
                && Objects.equals(enclosingType, that.enclosingType)
                && Objects.equals(name, that.name)
                && equalsTypeArgs(that.typeArguments);
    }

    private boolean equalsTypeArgs(final List<IType> typeArgs) {
        if (this.typeArguments.size() != typeArgs.size()) {
            return false;
        } else {
            return CollectionUtils.pairStream(this.typeArguments, typeArgs)
                    .allMatch(pair -> pair.first().equals(pair.second()));
        }
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                getKind(),
                enclosingType,
                name,
                typeArguments
        );
    }

    @Override
    public <R, P> R accept(final ITypeVisitor<R, P> visitor, final P param) {
        return visitor.visitReferenceType(this, param);
    }

    @Override
    public String toString() {
        return name;
    }
}

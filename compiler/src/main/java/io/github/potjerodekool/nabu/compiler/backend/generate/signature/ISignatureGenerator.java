package io.github.potjerodekool.nabu.compiler.backend.generate.signature;

import io.github.potjerodekool.nabu.compiler.backend.ir.type.*;

import java.util.List;
import java.util.stream.Collectors;

public abstract class ISignatureGenerator {

    public abstract String getDescriptor(final IType type);

    public abstract String getMethodDescriptor(final List<? extends IType> parameterTypes,
                                               final IType returnType);

    public String getMethodSignature(final List<? extends IType> parameterTypes,
                                     final IType returnType) {
        if (parameterTypes.stream()
                .noneMatch(this::isGenericType)
                && !isGenericType(returnType)) {
            return null;
        }

        final var params = parameterTypes.stream()
                .map(pt -> pt.accept(IStandardSignatureGeneratorVisitor.INSTANCE, null))
                .collect(Collectors.joining("", "(", ")"));

        final var retType = returnType.accept(IStandardSignatureGeneratorVisitor.INSTANCE, null);
        return params + retType;
    }

    public String getFieldSignature(final IType type) {
        if (isGenericType(type)) {
            return getSignature(type);
        } else {
            return null;
        }
    }

    protected boolean isGenericType(final IType type) {
        if (type instanceof IReferenceType referenceType) {
            return referenceType.getTypeArguments() != null;
        } else {
            return false;
        }
    }

    public String getSignature(final IType type) {
        return switch (type) {
            case IReferenceType referenceType -> getSignature(referenceType);
            case IPrimitiveType primitiveType -> getSignature(primitiveType);
            case IWildcardType wildcardType -> getSignature(wildcardType);
            case ITypeVariable typeVariable -> getSignature(typeVariable);
            case IIntersectionType intersectionType -> getSignature(intersectionType);
            case IArrayType arrayType -> getSignature(arrayType);
        };
    }

    public String getSignature(final IReferenceType referenceType) {
        return getSignature(referenceType, "");
    }

    public String getSignature(final IIntersectionType intersectionType) {
        return intersectionType.getBounds().stream()
                .map(referenceType -> (IReferenceType) referenceType)
                .map(referenceType -> {
                    final var prefix = referenceType.getKind() == ITypeKind.INTERFACE
                            ? ":"
                            : "";
                    return getSignature(referenceType, prefix);
                })
                .collect(Collectors.joining(""));
    }

    protected String getSignature(final IReferenceType referenceType,
                                  final String prefix) {
        if (!referenceType.getTypeArguments().isEmpty()) {
            final var typeArgs = referenceType.getTypeArguments().stream()
                    .map(this::getSignature)
                    .collect(Collectors.joining("", "<", ">"));
            var name = getDescriptor(referenceType);
            name = name.substring(0, name.length() - 1);
            return prefix + name + typeArgs + ";";
        } else {
            return prefix + getDescriptor(referenceType);
        }
    }

    protected abstract String getSignature(final IPrimitiveType primitiveType);

    public String getSignature(final IWildcardType wildcardType) {
        final var boundSignature = wildcardType.getBound() != null
                ? getSignature(wildcardType.getBound())
                : null;

        return switch (wildcardType.getBoundKind()) {
            case EXTENDS -> "+" + boundSignature;
            case SUPER -> "-" + boundSignature;
            case UNBOUND -> "*";
        };
    }

    public String getSignature(final ITypeVariable typeVariable) {
        final var name = typeVariable.getName();

        if (typeVariable.getUpperBound() != null) {
            return "T" + name + ":" + getSignature(typeVariable.getUpperBound());
        } else if (typeVariable.getLowerBound() != null) {
            return "T" + name + "-" + getSignature(typeVariable.getLowerBound());
        } else {
            return "T" + name + ";";
        }
    }

    public String getSignature(final IArrayType arrayType) {
        final var componentSignature = getSignature(arrayType.getComponentType());
        return "[" + componentSignature;
    }

}

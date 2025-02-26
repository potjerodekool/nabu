package io.github.potjerodekool.nabu.compiler.resolve.types;

import io.github.potjerodekool.nabu.compiler.type.*;

public class ContainsType extends TypeRelation {

    private final IsSubType isSubType;

    public ContainsType(final IsSubType isSubType) {
        this.isSubType = isSubType;
    }

    @Override
    public Boolean visitUnknownType(final TypeMirror typeMirror, final TypeMirror param) {
        return false;
    }

    @Override
    public Boolean visitWildcardType(final WildcardType wildcardType, final TypeMirror otherType) {
        return isSameWildcard(wildcardType, otherType)
                || isCaptureOf(otherType, wildcardType)
                || ((wildcardType.isExtendsBound() || isSubtypeNoCapture(getLowerBound(wildcardType), getLowerBound(otherType)))
                    &&
                    (wildcardType.isSuperBound() || isSubtypeNoCapture(getUpperBound(otherType), getUpperBound(wildcardType)))
                );
    }

    private TypeMirror getLowerBound(final TypeMirror type) {
        if (type instanceof WildcardType wildcardType) {
            return wildcardType.isExtendsBound()
                    ? null
                    : getLowerBound(wildcardType.getBound());
        } else {
            return type;
        }
    }

    public TypeMirror getUpperBound(final TypeMirror type) {
        if (type instanceof WildcardType wildcardType) {
            if (wildcardType.isSuperBound()) {
                return wildcardType.getBound() != null
                        ? null
                        : null;
            } else {
                return getUpperBound(wildcardType.getBound());
            }
        } else {
            return type;
        }
    }

    private boolean isSameWildcard(final WildcardType wildcardType,
                                   final TypeMirror type) {
        if (type.getKind() != TypeKind.WILDCARD) {
            return false;
        }

        final var otherWildCard = (WildcardType) type;
        return wildcardType.getBoundKind() == otherWildCard.getBoundKind()
                && wildcardType.getBound() == otherWildCard.getBound();
    }

    private boolean isCaptureOf(final TypeMirror otherType,
                                final WildcardType wildcardType) {
        if (!(otherType instanceof TypeVariable typeVariable)
            || !typeVariable.isCaptured()) {
            return false;
        }

        return isSameWildcard(
                wildcardType,
                ((CapturedType)otherType).getWildcard()
        );
    }

    public final boolean isSubtypeNoCapture(final TypeMirror t1,
                                            final TypeMirror t2) {
        return t1.accept(isSubType, t2);
    }
}

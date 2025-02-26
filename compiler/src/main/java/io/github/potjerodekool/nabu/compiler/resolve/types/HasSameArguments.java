package io.github.potjerodekool.nabu.compiler.resolve.types;

import io.github.potjerodekool.nabu.compiler.type.ExecutableType;
import io.github.potjerodekool.nabu.compiler.type.TypeKind;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;
import io.github.potjerodekool.nabu.compiler.util.CollectionUtils;

import java.util.List;

public class HasSameArguments extends TypeRelation {

    private final IsSameType isSameType;
    private final ContainsType containsType;
    private final boolean strict;

    public HasSameArguments(final IsSameType isSameType,
                            final ContainsType containsType,
                            final boolean strict) {
        this.isSameType = isSameType;
        this.containsType = containsType;
        this.strict = strict;
    }

    @Override
    public Boolean visitUnknownType(final TypeMirror typeMirror, final TypeMirror param) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Boolean visitMethodType(final ExecutableType methodType, final TypeMirror otherType) {
        return otherType.getKind() == TypeKind.EXECUTABLE
                && containsTypeEquivalent(
                methodType.getParameterTypes(),
                otherType.getParameterTypes()
        );
    }

    public boolean containsTypeEquivalent(final List<? extends TypeMirror> argumentTypes,
                                          final List<? extends TypeMirror> otherArgumentTypes) {
        if (argumentTypes.size() != otherArgumentTypes.size()) {
            return false;
        }

        return CollectionUtils.pairStream(argumentTypes, otherArgumentTypes)
                .allMatch(pair -> containsTypeEquivalent(pair.first(), pair.second())
        );
    }

    private boolean containsTypeEquivalent(final TypeMirror type,
                                           final TypeMirror otherType) {
        return isSameType(type, otherType) ||
                containsType(type, otherType) && containsType(otherType, type);
    }

    private boolean isSameType(final TypeMirror typeMirror,
                               final TypeMirror otherTypeMirror) {
        return isSameType.visit(typeMirror, otherTypeMirror);
    }

    private boolean containsType(final TypeMirror typeMirror,
                                 final TypeMirror otherTypeMirror) {
        return containsType.visitType(typeMirror, otherTypeMirror);
    }
}

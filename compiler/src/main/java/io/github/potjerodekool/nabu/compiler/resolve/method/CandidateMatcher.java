package io.github.potjerodekool.nabu.compiler.resolve.method;

import io.github.potjerodekool.nabu.compiler.ast.element.TypeElement;
import io.github.potjerodekool.nabu.compiler.type.DeclaredType;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;
import io.github.potjerodekool.nabu.compiler.type.TypeVisitor;
import io.github.potjerodekool.nabu.compiler.util.Types;

public class CandidateMatcher implements TypeVisitor<Boolean, TypeMirror> {

    private final Types types;
    private TypeMirror argumentType;

    public CandidateMatcher(final Types types) {
        this.types = types;
    }

    public void setArgumentType(final TypeMirror argumentType) {
        this.argumentType = argumentType;
    }

    @Override
    public Boolean visitUnknownType(final TypeMirror typeA, final TypeMirror typeB) {
        return Boolean.TRUE;
    }

    @Override
    public Boolean visitDeclaredType(final DeclaredType bestMatchParameterType,
                                     final TypeMirror candidateParameterType) {
        if (types.isSameType(argumentType, bestMatchParameterType)) {
            return types.isSameType(argumentType, candidateParameterType);
        } else {
            if (types.isSameType(argumentType, candidateParameterType)) {
                return true;
            } else {
                if (types.isSubType(argumentType, bestMatchParameterType)) {
                    if (types.isSubType(argumentType, candidateParameterType)) {
                        final var bestMatchSuperOffset = getSuperClassOffset(argumentType, bestMatchParameterType);
                        final var candidateSuperOffset = getSuperClassOffset(argumentType, (DeclaredType) candidateParameterType);
                        return bestMatchSuperOffset <= candidateSuperOffset;
                    } else {
                        return false;
                    }
                } else {
                    return types.isSubType(argumentType, candidateParameterType);
                }
            }
        }
    }

    private int getSuperClassOffset(final TypeMirror argumentType,
                                    final DeclaredType parameterType) {
        int offset = 0;
        TypeElement argumentTypeElement = argumentType.asTypeElement();
        final var parameterTypeElement = parameterType.asTypeElement();

        while (argumentTypeElement != parameterTypeElement) {
            argumentTypeElement = argumentTypeElement.getSuperclass().asTypeElement();
            offset++;
        }

        return offset;
    }

}

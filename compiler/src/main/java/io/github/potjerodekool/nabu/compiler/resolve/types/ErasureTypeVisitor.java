package io.github.potjerodekool.nabu.compiler.resolve.types;

import io.github.potjerodekool.nabu.compiler.TodoException;
import io.github.potjerodekool.nabu.compiler.type.*;

import java.util.ArrayList;

public class ErasureTypeVisitor implements TypeVisitor<TypeMirror, Void> {

    private final Types types;

    public ErasureTypeVisitor(final Types types) {
        this.types = types;
    }


    @Override
    public TypeMirror visitUnknownType(final TypeMirror typeMirror, final Void param) {
        return typeMirror;
    }

    @Override
    public TypeMirror visitArrayType(final ArrayType arrayType, final Void param) {
        final var componentType = arrayType.getComponentType().accept(this, param);
        return types.getArrayType(componentType);
    }

    @Override
    public TypeMirror visitMethodType(final ExecutableType methodType, final Void param) {
        final var typeVariables = new ArrayList<TypeVariable>(methodType.getTypeVariables());

        final var argumentTypes = methodType.getParameterTypes().stream()
                .map(at -> at.accept(this, null))
                .toList();

        final var returnType = methodType.getReturnType().accept(this, null);
        final var thrownTypes = methodType.getThrownTypes().stream()
                .map(tt -> tt.accept(this, null))
                .toList();

        return types.getExecutableType(
                methodType.getMethodSymbol(),
                typeVariables,
                returnType,
                argumentTypes,
                thrownTypes
        );
    }

    @Override
    public TypeMirror visitVariableType(final VariableType variableType, final Void param) {
        return variableType.getInterferedType().accept(this, param);
    }

    @Override
    public TypeMirror visitWildcardType(final WildcardType wildcardType, final Void param) {
        if (wildcardType.getExtendsBound() != null) {
            return wildcardType.getExtendsBound().accept(this, param);
        } else if (wildcardType.getSuperBound() != null) {
            return wildcardType.getSuperBound().accept(this, param);
        } else {
            throw new TodoException();
        }
    }

    @Override
    public TypeMirror visitTypeVariable(final TypeVariable typeVariable, final Void param) {
        if (typeVariable.getUpperBound() != null) {
            return typeVariable.getUpperBound().accept(this, param);
        } else if (typeVariable.getLowerBound() != null) {
            return typeVariable.getLowerBound().accept(this, param);
        } else {
            throw new TodoException();
        }
    }

}

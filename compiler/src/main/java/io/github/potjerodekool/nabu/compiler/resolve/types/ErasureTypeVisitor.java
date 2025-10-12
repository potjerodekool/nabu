package io.github.potjerodekool.nabu.compiler.resolve.types;

import io.github.potjerodekool.nabu.tools.TodoException;
import io.github.potjerodekool.nabu.compiler.resolve.impl.SymbolTable;
import io.github.potjerodekool.nabu.type.*;
import io.github.potjerodekool.nabu.util.Types;

import java.util.ArrayList;

public class ErasureTypeVisitor implements TypeVisitor<TypeMirror, Boolean> {

    private final Types types;
    private final SymbolTable symbolTable;

    public ErasureTypeVisitor(final Types types,
                              final SymbolTable symbolTable) {
        this.types = types;
        this.symbolTable = symbolTable;
    }


    @Override
    public TypeMirror visitUnknownType(final TypeMirror typeMirror, final Boolean recurse) {
        return typeMirror;
    }

    @Override
    public TypeMirror visitType(final TypeMirror typeMirror, final Boolean recurse) {
        return typeMirror.accept(this, recurse);
    }

    @Override
    public TypeMirror visitArrayType(final ArrayType arrayType, final Boolean recurse) {
        final var componentType = arrayType.getComponentType().accept(this, recurse);
        return types.getArrayType(componentType);
    }

    @Override
    public TypeMirror visitMethodType(final ExecutableType methodType, final Boolean recurse) {
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
    public TypeMirror visitVariableType(final VariableType variableType, final Boolean recurse) {
        return variableType.getInterferedType().accept(this, recurse);
    }

    @Override
    public TypeMirror visitWildcardType(final WildcardType wildcardType, final Boolean recurse) {
        final TypeMirror erased = erasure(wildUpperBound(wildcardType), recurse);

        if (wildcardType.getExtendsBound() != null) {
            return wildcardType.getExtendsBound().accept(this, recurse);
        } else if (wildcardType.getSuperBound() != null) {
            return wildcardType.getSuperBound().accept(this, recurse);
        } else {
            throw new TodoException();
        }
    }

    private TypeMirror wildUpperBound(final TypeMirror typeMirror) {
        if (typeMirror instanceof WildcardType wildcardType) {
            if (wildcardType.isSuperBound()) {
                return wildcardType.getBound() != null
                        ? wildcardType.getBound().getUpperBound()
                        : symbolTable.getObjectType();
            } else {
                return wildUpperBound(wildcardType.getBound());
            }
        } else {
            return typeMirror;
        }
    }

    public TypeMirror erasure(final TypeMirror typeMirror,
                              final boolean recurse) {
        if (typeMirror.isPrimitiveType()) {
            return typeMirror;
        } else {
            return this.visitType(typeMirror, recurse);
        }
    }

    @Override
    public TypeMirror visitTypeVariable(final TypeVariable typeVariable, final Boolean recurse) {
        if (typeVariable.getUpperBound() != null) {
            return typeVariable.getUpperBound().accept(this, recurse);
        } else if (typeVariable.getLowerBound() != null) {
            return typeVariable.getLowerBound().accept(this, recurse);
        } else {
            throw new TodoException();
        }
    }

}

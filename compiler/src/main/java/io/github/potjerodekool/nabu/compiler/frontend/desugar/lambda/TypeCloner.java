package io.github.potjerodekool.nabu.compiler.frontend.desugar.lambda;

import io.github.potjerodekool.nabu.compiler.TodoException;
import io.github.potjerodekool.nabu.compiler.ast.element.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.resolve.Types;
import io.github.potjerodekool.nabu.compiler.type.*;
import io.github.potjerodekool.nabu.compiler.type.immutable.ImmutablePrimitiveType;
import io.github.potjerodekool.nabu.compiler.type.immutable.ImmutableTypeVariable;
import io.github.potjerodekool.nabu.compiler.type.immutable.ImmutableVoidType;
import io.github.potjerodekool.nabu.compiler.type.immutable.ImmutableWildcardType;
import io.github.potjerodekool.nabu.compiler.type.mutable.MutableVariableType;

class TypeCloner implements TypeVisitor<TypeMirror, Object> {

    private final Types types;

    TypeCloner(final Types types) {
        this.types = types;
    }

    @Override
    public TypeMirror visitArrayType(final ArrayType arrayType, final Object param) {
        throw new TodoException();
    }

    @Override
    public TypeMirror visitClassType(final ClassType classType, final Object param) {
        final var clazz = (ClassSymbol) classType.asElement();

        final TypeMirror[] parameterTypes;

        if (classType.getParameterTypes() != null) {
            parameterTypes = classType.getParameterTypes().stream()
                    .map(pt -> pt.accept(this, param))
                    .toArray(TypeMirror[]::new);
        } else {
            parameterTypes = new TypeMirror[0];
        }

        return types.getDeclaredType(
                clazz,
                parameterTypes
        );

    }

    @Override
    public TypeMirror visitMethodType(final MethodType methodType, final Object param) {
        throw new TodoException();

    }

    @Override
    public TypeMirror visitVoidType(final VoidType voidType, final Object param) {
        if (voidType instanceof ImmutableVoidType) {
            return voidType;
        } else {
            throw new TodoException();
        }
    }

    @Override
    public TypeMirror visitPrimitiveType(final PrimitiveType primitiveType, final Object param) {
        if (primitiveType instanceof ImmutablePrimitiveType) {
            return primitiveType;
        } else {
            throw new TodoException();
        }
    }

    @Override
    public TypeMirror visitNullType(final NullType nullType, final Object param) {
        return types.getNullType();
    }

    @Override
    public TypeMirror visitVariableType(final VariableType variableType, final Object param) {
        final var interferedType = variableType.getInterferedType().accept(this, param);
        return new MutableVariableType(interferedType);
    }

    @Override
    public TypeMirror visitWildcardType(final WildcardType wildcardType, final Object param) {
        var extendsBound = wildcardType.getExtendsBound();
        var superBound = wildcardType.getSuperBound();

        if (extendsBound != null) {
            extendsBound = extendsBound.accept(this, param);
        }

        if (superBound != null) {
            superBound = superBound.accept(this, param);
        }

        return new ImmutableWildcardType(
                extendsBound,
                superBound
        );
    }

    @Override
    public TypeMirror visitTypeVariable(final TypeVariable typeVariable, final Object param) {
        final var upperBound = accept(typeVariable.getUpperBound());
        final var lowerBound = accept(typeVariable.getLowerBound());
        return new ImmutableTypeVariable(
                typeVariable.asElement().getSimpleName(),
                upperBound,
                lowerBound
        );
    }

    private TypeMirror accept(final TypeMirror typeMirror) {
        return typeMirror != null
                ? typeMirror.accept(this, null)
                : null;
    }
}

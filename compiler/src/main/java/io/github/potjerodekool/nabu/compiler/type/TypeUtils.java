package io.github.potjerodekool.nabu.compiler.type;

import io.github.potjerodekool.nabu.compiler.TodoException;
import io.github.potjerodekool.nabu.compiler.ast.element.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.type.immutable.*;
import lombok.extern.java.Log;

import java.util.Objects;

@Log
public final class TypeUtils implements TypeVisitor<TypeMirror, Void> {

    public static final TypeUtils INSTANCE = new TypeUtils();

    private TypeUtils() {
    }

    public String getClassName(final TypeMirror typeMirror) {
        if (typeMirror instanceof ClassType classType) {
            final var clazz = (ClassSymbol) classType.asElement();
            final var className = clazz.getQualifiedName();

            if (classType.getOuterType() == null) {
                return className;
            }

            final var outerName = getClassName(classType.getOuterType());

            return outerName + "$" + className;
        } else {
            return "";
        }
    }

    public ClassType asClassType(final TypeMirror typeMirror) {
        if (typeMirror instanceof ClassType classType) {
            return classType;
        } else if (typeMirror instanceof VariableType variableType) {
            return asClassType(variableType.getInterferedType());
        } else {
            throw new TodoException();
        }
    }

    public <TM extends TypeMirror> TypeMirror toImmutableType(final TM type) {
        if (type == null) {
            log.warning("type is null");
            return null;
        }

        return type.accept(this, null);
    }

    @Override
    public TypeMirror visitArrayType(final ArrayType arrayType, final Void param) {
        Objects.requireNonNull(arrayType.getComponentType());

        final var componentType = arrayType.getComponentType().accept(this, param);
        return new ImmutableArrayType(componentType);
    }

    @Override
    public TypeMirror visitClassType(final ClassType classType, final Void param) {
        final var parameterTypes = classType.getParameterTypes() != null
                ? classType.getParameterTypes().stream()
                        .map(pt -> pt.accept(this, param))
                                .toList()
                : null;

        final var outerType = (ClassType) accept(classType.getOuterType());
        return new ImmutableClassType(
                classType.asElement(),
                outerType,
                parameterTypes
        );
    }

    @Override
    public TypeMirror visitMethodType(final MethodType methodType, final Void param) {
        throw new TodoException();
    }

    @Override
    public TypeMirror visitVoidType(final VoidType voidType, final Void param) {
        throw new TodoException();
    }

    @Override
    public TypeMirror visitPrimitiveType(final PrimitiveType primitiveType, final Void param) {
        if (primitiveType instanceof ImmutablePrimitiveType) {
            return primitiveType;
        } else {
            throw new TodoException();
        }
    }

    @Override
    public TypeMirror visitNullType(final NullType nullType, final Void param) {
        return nullType;
    }

    @Override
    public TypeMirror visitVariableType(final VariableType variableType, final Void param) {
        throw new TodoException();
    }

    @Override
    public TypeMirror visitWildcardType(final WildcardType wildcardType, final Void param) {
        final var extendsBound = accept(wildcardType.getExtendsBound());
        final var superBound = accept(wildcardType.getSuperBound());

        return new ImmutableWildcardType(
                extendsBound,
                superBound
        );
    }

    @Override
    public TypeMirror visitTypeVariable(final TypeVariable typeVariable, final Void param) {
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

package io.github.potjerodekool.nabu.compiler.resolve.method.impl;

import io.github.potjerodekool.nabu.tools.TodoException;
import io.github.potjerodekool.nabu.type.*;
import io.github.potjerodekool.nabu.util.Types;

import java.util.Map;

public class SimpleTypeMapApplier implements TypeVisitor<TypeMirror, Void> {

    private final Map<String, TypeMirror> map;
    private final Types types;

    public SimpleTypeMapApplier(final Map<String, TypeMirror> map,
                                final Types types) {
        this.map = map;
        this.types = types;
    }

    public static TypeMirror apply(final Map<String, TypeMirror> map,
                                   final TypeMirror typeMirror,
                                   final Types types) {
        final var applier = new  SimpleTypeMapApplier(map, types);
        return typeMirror.accept(applier, null);
    }

    @Override
    public TypeMirror visitUnknownType(final TypeMirror typeMirror, final Void param) {
        throw new TodoException("" + typeMirror);
    }

    @Override
    public TypeMirror visitPrimitiveType(final PrimitiveType primitiveType, final Void param) {
        return primitiveType;
    }

    @Override
    public TypeMirror visitNoType(final NoType noType, final Void param) {
        return noType;
    }

    @Override
    public TypeMirror visitDeclaredType(final DeclaredType declaredType, final Void param) {
        final var typeArguments = declaredType.getTypeArguments();

        if (typeArguments.isEmpty()) {
            return declaredType;
        } else {
            final var mapped = typeArguments.stream()
                    .map(typeArg -> typeArg.accept(this, param))
                    .toArray(TypeMirror[]::new);

            return types.getDeclaredType(
                    declaredType.asTypeElement(),
                    mapped
            );
        }
    }

    @Override
    public TypeMirror visitTypeVariable(final TypeVariable typeVariable, final Void param) {
        final var name = typeVariable.asElement().getSimpleName();
        return map.getOrDefault(name, typeVariable);
    }

    @Override
    public TypeMirror visitMethodType(final ExecutableType methodType, final Void param) {
        final var returnType = methodType.getReturnType().accept(this, param);
        final var parameterTypes = methodType.getParameterTypes().stream()
                .map(paramType -> paramType.accept(this, param))
                .toList();
        final var thrownTypes = methodType.getThrownTypes().stream()
                .map(thrownType -> thrownType.accept(this, param))
                .toList();

        return types.getExecutableType(
                methodType.getMethodSymbol(),
                methodType.getTypeVariables(),
                returnType,
                parameterTypes,
                thrownTypes
        );
    }

    @Override
    public TypeMirror visitArrayType(final ArrayType arrayType, final Void param) {
        final var componentType = arrayType.getComponentType().accept(this, param);

        if (componentType == arrayType.getComponentType()) {
            return arrayType;
        } else {
            return types.getArrayType(componentType);
        }
    }

    @Override
    public TypeMirror visitWildcardType(final WildcardType wildcardType, final Void param) {
            return switch (wildcardType.getBoundKind()) {
                case EXTENDS -> types.getWildcardType(
                        wildcardType.getExtendsBound().accept(this, param),
                        null
                );
                case SUPER ->
                    types.getWildcardType(
                            null,
                            wildcardType.getSuperBound().accept(this, param)
                    );
                  case UNBOUND ->
                    types.getWildcardType(
                            null,
                            null
                    );
            };
        }
    }


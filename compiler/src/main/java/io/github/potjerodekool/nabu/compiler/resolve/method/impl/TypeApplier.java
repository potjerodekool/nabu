package io.github.potjerodekool.nabu.compiler.resolve.method.impl;

import io.github.potjerodekool.nabu.type.*;
import io.github.potjerodekool.nabu.util.Types;

class TypeApplier implements TypeVisitor<TypeMirror, TypeMirror> {

    private final TypeMap typeMap;
    private final Types types;

    TypeApplier(final TypeMap typeMap, final Types types) {
        this.typeMap = typeMap;
        this.types = types;
    }

    @Override
    public TypeMirror visitUnknownType(final TypeMirror typeMirror,
                                       final TypeMirror param) {
        return typeMirror;
    }

    @Override
    public TypeMirror visitDeclaredType(final DeclaredType declaredType,
                                        final TypeMirror param) {
        final var typeArguments = declaredType.getTypeArguments();

        if (typeArguments.isEmpty()) {
            return declaredType;
        }

        final var mapped = typeArguments.stream()
                .map(typeArg -> typeArg.accept(this, param))
                .toArray(TypeMirror[]::new);

        return types.getDeclaredType(
                declaredType.asTypeElement(),
                mapped
        );
    }

    @Override
    public TypeMirror visitTypeVariable(final TypeVariable typeVariable,
                                        final TypeMirror param) {
        final var name = typeVariable.asElement().getSimpleName();
        return typeMap.getOrDefault(name, typeVariable);
    }

    @Override
    public TypeMirror visitWildcardType(final WildcardType wildcardType,
                                        final TypeMirror param) {
        return switch (wildcardType.getBoundKind()) {
            case EXTENDS -> types.getWildcardType(
                    wildcardType.getExtendsBound().accept(this, param),
                    null
            );
            case SUPER -> types.getWildcardType(
                    null,
                    wildcardType.getSuperBound().accept(this, param)
            );
            case UNBOUND -> types.getWildcardType(null, null);
        };
    }

    @Override
    public TypeMirror visitMethodType(final ExecutableType methodType,
                                      final TypeMirror param) {
        final var returnType = methodType.getReturnType() != null
                ? methodType.getReturnType().accept(this, param)
                : null;
        final var parameterTypes = methodType.getParameterTypes().stream()
                .map(pt -> pt != null ? pt.accept(this, param) : null)
                .toList();
        final var thrownTypes = methodType.getThrownTypes().stream()
                .map(tt -> tt != null ? tt.accept(this, param) : null)
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
    public TypeMirror visitArrayType(final ArrayType arrayType,
                                     final TypeMirror param) {
        final var componentType = arrayType.getComponentType().accept(this, param);
        if (componentType == arrayType.getComponentType()) {
            return arrayType;
        }
        return types.getArrayType(componentType);
    }

    @Override
    public TypeMirror visitPrimitiveType(final PrimitiveType primitiveType,
                                         final TypeMirror param) {
        return primitiveType;
    }

    @Override
    public TypeMirror visitNoType(final NoType noType,
                                  final TypeMirror param) {
        return noType;
    }
}

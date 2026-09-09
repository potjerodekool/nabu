package io.github.potjerodekool.nabu.compiler.resolve.method.impl;

import io.github.potjerodekool.nabu.type.*;
import io.github.potjerodekool.nabu.util.CollectionUtils;
import io.github.potjerodekool.nabu.util.Types;

class TypeMapFiller implements TypeVisitor<TypeMirror, TypeMirror> {

    private final TypeMap typeMap = new TypeMap();
    private final Types types;

    TypeMapFiller(final Types types) {
        this.types = types;
    }

    public TypeMap getTypeMap() {
        return typeMap;
    }

    @Override
    public TypeMirror visitUnknownType(final TypeMirror typeMirror,
                                       final TypeMirror param) {
        return typeMirror;
    }

    @Override
    public TypeMirror visitDeclaredType(final DeclaredType declaredType,
                                        final TypeMirror otherType) {
        final var typeArguments = declaredType.getTypeArguments();
        final var typeParameters = declaredType.asTypeElement()
                .getTypeParameters();
        final var typeParameterCount = typeParameters.size();

        if (otherType instanceof DeclaredType otherDeclaredType) {
            final var otherTypeArguments = otherDeclaredType.getTypeArguments();
            final var typeArgumentCount = typeArguments.size();

            if (typeArguments.size() == otherTypeArguments.size()) {
                for (int index = 0; index < typeArgumentCount; index++) {
                    final var typeArg = typeArguments.get(index);
                    final var otherTypeArg = otherTypeArguments.get(index).accept(this, null);
                    typeArg.accept(this, otherTypeArg);
                }
            }
        } else if (otherType instanceof ExecutableType otherExecutableType) {
            final var functionalMethod = declaredType.asTypeElement().findFunctionalMethod();

            if (functionalMethod != null) {
                return functionalMethod.asType().accept(this, otherExecutableType);
            }
        } else if (otherType instanceof PrimitiveType otherPrimitiveType) {

        } else {
            if (typeArguments.size() == typeParameterCount) {
                for (int index = 0; index < typeParameterCount; index++) {
                    final var typeParameter = typeParameters.get(index);
                    final var name = typeParameter.getSimpleName();
                    final var typeArg = typeArguments.get(index);
                    this.addType(name, typeArg);
                }
            }
        }

        return declaredType;
    }

    @Override
    public TypeMirror visitWildcardType(final WildcardType wildcardType,
                                        final TypeMirror param) {
        return switch (wildcardType.getBoundKind()) {
            case EXTENDS -> wildcardType.getExtendsBound();
            case SUPER -> wildcardType.getSuperBound();
            case UNBOUND -> types.getObjectType();
        };
    }

    @Override
    public TypeMirror visitTypeVariable(final TypeVariable typeVariable,
                                        final TypeMirror param) {
        if (param != null) {
            final var name = typeVariable.asElement().getSimpleName();
            this.addType(name, param);
            return param;
        }
        return typeVariable;
    }

    private void addType(final String name,
                         final TypeMirror type) {
        this.typeMap.put(name, type);
    }

    @Override
    public TypeMirror visitMethodType(final ExecutableType methodType, final TypeMirror param) {
        if (param instanceof ExecutableType otherExecutableType) {
            if (methodType.getParameterTypes().size() == otherExecutableType.getParameterTypes().size()) {
                CollectionUtils.forEach(
                        methodType.getParameterTypes(),
                        otherExecutableType.getParameterTypes(),
                        (first, second) -> {
                            first.accept(this, second);
                        }
                );
            }
        }

        return null;
    }
}

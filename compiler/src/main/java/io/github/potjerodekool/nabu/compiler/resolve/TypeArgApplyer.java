package io.github.potjerodekool.nabu.compiler.resolve;

import io.github.potjerodekool.nabu.compiler.TodoException;
import io.github.potjerodekool.nabu.compiler.ast.element.ElementVisitor;
import io.github.potjerodekool.nabu.compiler.ast.element.ExecutableElement;
import io.github.potjerodekool.nabu.compiler.ast.element.TypeElement;
import io.github.potjerodekool.nabu.compiler.ast.element.TypeParameterElement;
import io.github.potjerodekool.nabu.compiler.type.*;

import java.util.ArrayList;
import java.util.Map;

class TypeArgApplyer implements ElementVisitor<TypeMirror, Map<String, TypeMirror>>,
        TypeVisitor<TypeMirror, Map<String, TypeMirror>> {

    private final Types types;

    public TypeArgApplyer(final Types types) {
        this.types = types;
    }

    @Override
    public TypeMirror visitUnknownType(final TypeMirror typeMirror, final Map<String, TypeMirror> param) {
        return typeMirror;
    }

    @Override
    public TypeMirror visitExecutableElement(final ExecutableElement methodSymbol,
                                             final Map<String, TypeMirror> typeArgMap) {
        final var methodType = (ExecutableType) methodSymbol.asType();
        final var returnType = methodType.getReturnType().accept(this, typeArgMap);
        final var argumentTypes = methodType.getParameterTypes().stream()
                .map(it -> it.accept(this, typeArgMap))
                .toList();

        final var typeVariables = new ArrayList<TypeVariable>(methodType.getTypeVariables());

        return types.getExecutableType(
                methodSymbol,
                typeVariables,
                returnType,
                argumentTypes,
                new ArrayList<>()
        );
    }

    @Override
    public TypeMirror visitTypeParameterElement(final TypeParameterElement typeParameterElement, final Map<String, TypeMirror> map) {
        throw new TodoException();
    }

    @Override
    public TypeMirror visitArrayType(final ArrayType arrayType, final Map<String, TypeMirror> typeArgMap) {
        final var componentType = arrayType.getComponentType().accept(this, typeArgMap);
        return types.getArrayType(componentType);
    }

    @Override
    public TypeMirror visitDeclaredType(final DeclaredType classType, final Map<String, TypeMirror> typeArgMap) {
        final var typeArgs = classType.getTypeArguments().stream()
                .map(it -> it.accept(this, typeArgMap))
                .toArray(TypeMirror[]::new);

        return types.getDeclaredType(
                (TypeElement) classType.asElement(),
                typeArgs
        );
    }

    @Override
    public TypeMirror visitWildcardType(final WildcardType wildcardType, final Map<String, TypeMirror> typeArgMap) {
        final var extendsBound = wildcardType.getExtendsBound() != null
                ? wildcardType.getExtendsBound().accept(this, typeArgMap)
                : null;

        final var superBound = wildcardType.getSuperBound() != null
                ? wildcardType.getSuperBound().accept(this, typeArgMap)
                : null;

        return types.getWildcardType(
                extendsBound,
                superBound
        );
    }

    @Override
    public TypeMirror visitTypeVariable(final TypeVariable typeVariable, final Map<String, TypeMirror> typeArgMap) {
        final var name = typeVariable.asElement().getSimpleName();
        return typeArgMap.getOrDefault(name, typeVariable);
    }

}

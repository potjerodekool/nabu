package io.github.potjerodekool.nabu.compiler.resolve.types;

import io.github.potjerodekool.nabu.compiler.type.impl.AbstractType;
import io.github.potjerodekool.nabu.type.TypeMirror;
import io.github.potjerodekool.nabu.type.TypeVariable;
import io.github.potjerodekool.nabu.util.CollectionUtils;
import io.github.potjerodekool.nabu.util.Types;

import java.util.HashMap;

public class InterfaceTypeVisitor extends AbstractTypeVisitor<TypeMirror, TypeMirror> {

    private final Types types;

    public InterfaceTypeVisitor(final Types types) {
        this.types = types;
    }

    @Override
    public TypeMirror visitUnknownType(final TypeMirror interfaceType, final TypeMirror subType) {
        return interfaceType;
    }

    @Override
    public TypeMirror visitType(final TypeMirror interfaceType, final TypeMirror subType) {
        final var abstractSubType = ((AbstractType)subType);
        final var typeArguments = abstractSubType.getTypeArguments();
        final var typeParameters = subType.asTypeElement().getTypeParameters();

        final var map = new HashMap<String, TypeMirror>();

        CollectionUtils.forEach(
                typeArguments,
                typeParameters,
                (typeArg, typeParam) -> map.put(typeParam.getSimpleName(), typeArg)
        );

        final var subTypeElement = subType.asTypeElement();
        final var origSubType = subTypeElement.asType();

        final var names = origSubType.getTypeArguments().stream()
                .flatMap(CollectionUtils.mapOnly(TypeVariable.class))
                .map(typeVariable -> typeVariable.asElement().getSimpleName())
                .toList();

        final var arguments = names.stream()
                .map(map::get)
                .toArray(TypeMirror[]::new);

        return types.getDeclaredType(
                interfaceType.asTypeElement(),
                arguments
        );
    }
}

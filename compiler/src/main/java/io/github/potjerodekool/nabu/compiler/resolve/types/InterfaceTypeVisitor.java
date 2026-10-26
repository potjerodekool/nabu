package io.github.potjerodekool.nabu.compiler.resolve.types;

import io.github.potjerodekool.nabu.compiler.type.impl.AbstractType;
import io.github.potjerodekool.nabu.type.AbstractTypeVisitor;
import io.github.potjerodekool.nabu.type.DeclaredType;
import io.github.potjerodekool.nabu.type.WildcardType;
import io.github.potjerodekool.nabu.type.TypeMirror;
import io.github.potjerodekool.nabu.type.TypeVariable;
import io.github.potjerodekool.nabu.util.CollectionUtils;
import io.github.potjerodekool.nabu.util.Types;

import java.util.HashMap;
import java.util.Map;

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

        // The type arguments of the interface as the interface declares them (Function<T, T> of
        // UnaryOperator<T>), with the type variables of the subtype replaced by its arguments.
        final var arguments = interfaceType.getTypeArguments().stream()
                .map(argument -> substitute(argument, map))
                .toArray(TypeMirror[]::new);

        return types.getDeclaredType(
                interfaceType.asTypeElement(),
                arguments
        );
    }

    private TypeMirror substitute(final TypeMirror type, final Map<String, TypeMirror> map) {
        if (type instanceof TypeVariable typeVariable) {
            final var replacement = map.get(typeVariable.asElement().getSimpleName());
            return replacement != null ? replacement : type;
        }
        if (type instanceof WildcardType wildcard) {
            if (wildcard.isExtendsBound()) {
                return types.getWildcardType(substitute(wildcard.getExtendsBound(), map), null);
            }
            if (wildcard.isSuperBound()) {
                return types.getWildcardType(null, substitute(wildcard.getSuperBound(), map));
            }
            return type;
        }
        if (type instanceof DeclaredType declared && !declared.getTypeArguments().isEmpty()
                && declared.asTypeElement() != null) {
            return types.getDeclaredType(
                    declared.asTypeElement(),
                    declared.getTypeArguments().stream()
                            .map(argument -> substitute(argument, map))
                            .toArray(TypeMirror[]::new)
            );
        }
        return type;
    }
}

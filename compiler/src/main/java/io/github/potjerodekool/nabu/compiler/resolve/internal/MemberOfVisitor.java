package io.github.potjerodekool.nabu.compiler.resolve.internal;

import io.github.potjerodekool.nabu.compiler.ast.element.Element;
import io.github.potjerodekool.nabu.compiler.ast.element.TypeElement;
import io.github.potjerodekool.nabu.compiler.type.DeclaredType;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;
import io.github.potjerodekool.nabu.compiler.type.TypeVisitor;
import io.github.potjerodekool.nabu.compiler.util.CollectionUtils;
import io.github.potjerodekool.nabu.compiler.util.Pair;
import io.github.potjerodekool.nabu.compiler.util.Types;

import java.util.stream.Collectors;

public class MemberOfVisitor implements TypeVisitor<TypeMirror, Element> {

    private final Types types;

    public MemberOfVisitor(final Types types) {
        this.types = types;
    }

    @Override
    public TypeMirror visitUnknownType(final TypeMirror typeMirror, final Element param) {
        throw new UnsupportedOperationException();
    }

    @Override
    public TypeMirror visitDeclaredType(final DeclaredType declaredType,
                                        final Element element) {
        final var typeArguments = declaredType.getTypeArguments();
        final var clazz = (TypeElement) declaredType.asElement();
        final var typeParameters = clazz.getTypeParameters();

        final var typeArgMap = CollectionUtils.pairStream(
                        typeParameters,
                        typeArguments
                ).map(pair -> {
                    final var typeParameter = pair.first();
                    final var typeArgument = pair.second();
                    final var name = typeParameter.getSimpleName();
                    return new Pair<>(name, typeArgument);
                })
                .collect(Collectors.toMap(
                        Pair::first,
                        Pair::second
                ));

        final var typeArgApplyer = new TypeArgApplyer(this.types);
        return element.accept(typeArgApplyer, typeArgMap);
    }

}


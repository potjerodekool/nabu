package io.github.potjerodekool.nabu.compiler.annotation.processing;

import io.github.potjerodekool.nabu.compiler.annotation.processing.java.element.ElementWrapperFactory;
import io.github.potjerodekool.nabu.compiler.annotation.processing.java.type.JAbstractType;
import io.github.potjerodekool.nabu.compiler.annotation.processing.java.type.JPrimitiveType;
import io.github.potjerodekool.nabu.compiler.annotation.processing.java.type.TypeWrapperFactory;
import io.github.potjerodekool.nabu.tools.TodoException;

import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.*;
import javax.lang.model.util.Types;
import java.util.Arrays;
import java.util.List;

public class JavacTypes implements Types {

    private final io.github.potjerodekool.nabu.util.Types nabuTypes;

    public JavacTypes(final io.github.potjerodekool.nabu.util.Types nabuTypes) {
        this.nabuTypes = nabuTypes;
    }

    @Override
    public Element asElement(final TypeMirror t) {
        return switch (t.getKind()) {
            case MODULE,
                 PACKAGE,
                 DECLARED,
                 INTERSECTION,
                 TYPEVAR,
                 ERROR -> {
                final var type = (JAbstractType<?>) t;
                yield type.asElement();
            }
            default -> null;
        };
    }

    @Override
    public boolean isSameType(final TypeMirror t1,
                              final TypeMirror t2) {
        return nabuTypes.isSameType(
                TypeWrapperFactory.unwrap(t1),
                TypeWrapperFactory.unwrap(t2)
        );
    }

    @Override
    public boolean isSubtype(final TypeMirror t1,
                             final TypeMirror t2) {
        return nabuTypes.isSubType(
                TypeWrapperFactory.unwrap(t1),
                TypeWrapperFactory.unwrap(t2)
        );
    }

    @Override
    public boolean isAssignable(final TypeMirror t1,
                                final TypeMirror t2) {
        return nabuTypes.isAssignable(
                TypeWrapperFactory.unwrap(t1),
                TypeWrapperFactory.unwrap(t2)
        );
    }

    @Override
    public boolean contains(final TypeMirror t1,
                            final TypeMirror t2) {
        return nabuTypes.contains(
                TypeWrapperFactory.unwrap(t1),
                TypeWrapperFactory.unwrap(t2)
        );
    }

    @Override
    public boolean isSubsignature(final ExecutableType m1,
                                  final ExecutableType m2) {
        return nabuTypes.isSubsignature(
                TypeWrapperFactory.unwrap(m1),
                TypeWrapperFactory.unwrap(m2)
        );
    }

    @Override
    public List<? extends TypeMirror> directSupertypes(final TypeMirror t) {
        return nabuTypes.directSupertypes(
                TypeWrapperFactory.unwrap(t)
        ).stream()
                .map(TypeWrapperFactory::wrap)
                .toList();
    }

    @Override
    public TypeMirror erasure(final TypeMirror t) {
        return TypeWrapperFactory.wrap(nabuTypes.erasure(TypeWrapperFactory.unwrap(t)));
    }

    @Override
    public TypeElement boxedClass(final PrimitiveType p) {
        return (TypeElement) ElementWrapperFactory.wrap(nabuTypes.boxedClass(TypeWrapperFactory.unwrap(p)));
    }

    @Override
    public PrimitiveType unboxedType(final TypeMirror t) {
        return (PrimitiveType) TypeWrapperFactory.wrap(nabuTypes.unboxedType(TypeWrapperFactory.unwrap(t)));
    }

    @Override
    public TypeMirror capture(final TypeMirror t) {
        return TypeWrapperFactory.wrap(nabuTypes.capture(TypeWrapperFactory.unwrap(t)));
    }

    @Override
    public PrimitiveType getPrimitiveType(final TypeKind kind) {
        final var nabuKind = io.github.potjerodekool.nabu.type.TypeKind.valueOf(kind.name());
        return new JPrimitiveType(kind, nabuTypes.getPrimitiveType(nabuKind));
    }

    @Override
    public NullType getNullType() {
        return (NullType) TypeWrapperFactory.wrap(nabuTypes.getNullType());
    }

    @Override
    public NoType getNoType(final TypeKind kind) {
        return (NoType) TypeWrapperFactory.wrap(
                nabuTypes.getNoType(io.github.potjerodekool.nabu.type.TypeKind.valueOf(kind.name()))
        );
    }

    @Override
    public ArrayType getArrayType(final TypeMirror componentType) {
        return (ArrayType) TypeWrapperFactory.wrap(nabuTypes.getArrayType(
                TypeWrapperFactory.unwrap(componentType)
        ));
    }

    @Override
    public WildcardType getWildcardType(final TypeMirror extendsBound,
                                        final TypeMirror superBound) {
        throw new TodoException();
    }

    @Override
    public DeclaredType getDeclaredType(final TypeElement typeElem,
                                        final TypeMirror... typeArgs) {
        final var nabuTypeArgs = Arrays.stream(typeArgs)
                        .map(TypeWrapperFactory::unwrap)
                                .toArray(io.github.potjerodekool.nabu.type.TypeMirror[]::new);
        final var declaredType = nabuTypes.getDeclaredType(
                (io.github.potjerodekool.nabu.lang.model.element.TypeElement) ElementWrapperFactory.unwrap(typeElem),
                nabuTypeArgs
        );

        return (DeclaredType) TypeWrapperFactory.wrap(declaredType);
    }

    @Override
    public DeclaredType getDeclaredType(final DeclaredType containing,
                                        final TypeElement typeElem,
                                        final TypeMirror... typeArgs) {
        final var nabuContaining = (io.github.potjerodekool.nabu.type.DeclaredType) TypeWrapperFactory.unwrap(containing);

        final var nabuTypeArgs = Arrays.stream(typeArgs)
                .map(TypeWrapperFactory::unwrap)
                .toArray(io.github.potjerodekool.nabu.type.TypeMirror[]::new);

        final var declaredType = nabuTypes.getDeclaredType(
                nabuContaining,
                (io.github.potjerodekool.nabu.lang.model.element.TypeElement) ElementWrapperFactory.unwrap(typeElem),
                nabuTypeArgs
        );

        return (DeclaredType) TypeWrapperFactory.wrap(declaredType);
    }

    @Override
    public TypeMirror asMemberOf(final DeclaredType containing,
                                 final Element element) {
        return TypeWrapperFactory.wrap(nabuTypes.asMemberOf(
                TypeWrapperFactory.unwrap(containing),
                ElementWrapperFactory.unwrap(element)
        ));
    }
}

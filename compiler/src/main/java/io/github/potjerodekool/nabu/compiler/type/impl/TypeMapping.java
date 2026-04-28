package io.github.potjerodekool.nabu.compiler.type.impl;

import io.github.potjerodekool.nabu.type.TypeMirror;

import java.util.List;
import java.util.function.Function;

public class TypeMapping<S> extends MapVisitor<S> implements Function<AbstractType, AbstractType> {

    @Override
    public AbstractType apply(final AbstractType type) {
        return visit(type);
    }

    public final List<TypeMirror> visit(final List<? extends TypeMirror> typeMirrors, S s) {
        return typeMirrors.stream()
                .map(typeMirror -> (TypeMirror) visit(typeMirror, s))
                .toList();
    }
}

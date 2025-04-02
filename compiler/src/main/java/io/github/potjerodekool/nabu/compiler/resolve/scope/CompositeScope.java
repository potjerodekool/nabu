package io.github.potjerodekool.nabu.compiler.resolve.scope;

import io.github.potjerodekool.nabu.compiler.ast.element.Element;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public class CompositeScope implements ImportScope {

    private final List<Scope> scopes;

    public CompositeScope(final Scope... scopes) {
        this.scopes = Arrays.asList(scopes);
    }

    @Override
    public void define(final Element element) {
    }

    @Override
    public Element resolve(final String name) {
        return null;
    }

    @Override
    public TypeMirror resolveType(final String name) {
        return this.scopes.stream()
                .map(it -> Optional.ofNullable(it.resolveType(name)))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst()
                .orElse(null);
    }
}

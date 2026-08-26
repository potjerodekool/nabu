package io.github.potjerodekool.nabu.resolve.scope;

import io.github.potjerodekool.nabu.lang.model.element.Element;
import io.github.potjerodekool.nabu.type.TypeMirror;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * A scope to consists of other scopes.
 */
public class CompositeScope implements Scope {

    private final List<Scope> scopes;

    public CompositeScope(final Scope... scopes) {
        this.scopes = Arrays.asList(scopes);
    }

    @Override
    public void define(final Element element) {
        //Elements shouldn't be defined in this scope.
    }

    @Override
    public Element resolve(final String name) {
        return null;
    }

    /**
     * @param name An element name.
     * @return Return a type from the scopes.
     */
    @Override
    public TypeMirror resolveType(final String name) {
        return this.scopes.stream()
                .map(it -> Optional.ofNullable(it.resolveType(name)))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst()
                .orElse(null);
    }

    /**
     * @return Returns all the elements defined in the scopes.
     */
    @Override
    public Collection<? extends Element> elements() {
        return scopes.stream()
                .flatMap(it -> it.elements().stream())
                .toList();
    }
}

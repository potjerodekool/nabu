package io.github.potjerodekool.nabu.resolve.scope;

import io.github.potjerodekool.nabu.lang.model.element.Element;
import io.github.potjerodekool.nabu.type.TypeMirror;

import java.util.*;
import java.util.function.Predicate;

/**
 * Base class for scopes.
 */
public abstract class AbstractScope implements Scope {

    //Map the element by simple name.
    private final Map<String, Element> locals = new LinkedHashMap<>();

    //The parent scope.
    private final Scope parent;

    public AbstractScope(final Scope parent) {
        this.parent = parent;
    }

    @Override
    public void define(final Element element) {
        locals.put(element.getSimpleName(), element);
    }

    @Override
    public Set<String> locals() {
        return locals.keySet();
    }

    @Override
    public Collection<Element> elements() {
        return locals.values();
    }

    @Override
    public Scope getParent() {
        return parent;
    }

    @Override
    public Element resolve(final String name) {
        return resolve(name, it -> true);
    }

    @Override
    public Element resolve(final String name,
                           final Predicate<Element> filter) {
        var element = this.locals.get(name);

        if (element != null && filter.test(element)) {
            return element;
        }

        if (parent != null) {
            return parent.resolve(name, filter);
        }

        return null;
    }

    @Override
    public TypeMirror resolveType(final String name) {
        final var element = locals.get(name);

        if (element == null) {
            if (parent != null) {
                return parent.resolveType(name);
            } else {
                return null;
            }
        }

        return element.getKind().isDeclaredType()
                ? element.asType()
                : null;
    }
}

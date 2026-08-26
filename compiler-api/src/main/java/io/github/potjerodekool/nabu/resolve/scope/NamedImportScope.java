package io.github.potjerodekool.nabu.resolve.scope;

import io.github.potjerodekool.nabu.lang.model.element.Element;
import io.github.potjerodekool.nabu.lang.model.element.QualifiedNameable;
import io.github.potjerodekool.nabu.type.TypeMirror;

import java.util.*;
import java.util.function.Predicate;

/**
 * Named import scope.
 */
public class NamedImportScope extends ImportScope {

    private final Map<String, Element> elements = new HashMap<>();

    @Override
    public void define(final Element element) {
        if (element instanceof QualifiedNameable qualifiedNameable) {
            elements.put(
                    qualifiedNameable.getQualifiedName(),
                    element
            );
        } else {
            elements.put(element.getSimpleName(), element);
        }
    }

    @Override
    public Element resolve(final String name) {
        var element = elements.get(name);

        if (element == null && !name.contains(".")) {
            final var postFix = "." + name;
            element = elements.keySet().stream()
                    .filter(key -> key.endsWith(postFix))
                    .map(elements::get)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);
        }

        return element;
    }

    @Override
    public TypeMirror resolveType(final String name) {
        var element = elements.get(name);

        if (element == null && !name.contains(".")) {
            final var postFix = "." + name;
            element = elements.keySet().stream()
                    .filter(key -> key.endsWith(postFix))
                    .map(elements::get)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);
        }

        if (element == null) {
            return null;
        } else {
            return element.asType();
        }
    }

    @Override
    public Iterable<Element> resolveByName(final String name, final Predicate<Element> filter) {
        final var symbol = this.elements.get(name);

        if (symbol != null) {
            return filter.test(symbol)
                    ? List.of(symbol)
                    : List.of();
        }

        return List.of();
    }

    @Override
    public Collection<? extends Element> elements() {
        return elements.values();
    }
}

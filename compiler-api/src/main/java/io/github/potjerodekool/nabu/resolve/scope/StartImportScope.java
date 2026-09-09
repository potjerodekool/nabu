package io.github.potjerodekool.nabu.resolve.scope;

import io.github.potjerodekool.nabu.lang.model.element.Element;
import io.github.potjerodekool.nabu.type.TypeMirror;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Scope for start imports (on-demand imports), like {@code import java.util.*;}
 * of both packages and class containers (bijv. {@code import a.b.Model.*;}).
 */
public class StartImportScope extends ImportScope {

    private final java.util.Map<String, Element> elements = new java.util.HashMap<>();

    @Override
    public void define(final Element element) {
        if (element != null) {
            elements.put(element.getSimpleName().toString(), element);
        }
    }

    @Override
    public Element resolve(final String name) {
        return resolveTypeElement(name)
                .orElse(null);
    }

    private java.util.Optional<Element> resolveTypeElement(final String name) {
        var element = elements.get(name);

        if (element == null) {
            element = elements.keySet().stream()
                    .filter(key -> key.endsWith("." + name) || key.endsWith("$" + name))
                    .map(elements::get)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);
        }

        return java.util.Optional.ofNullable(element);
    }

    @Override
    public TypeMirror resolveType(final String name) {
        return resolveTypeElement(name)
                .map(element -> element.asType())
                .orElse(null);
    }

    @Override
    public Collection<? extends Element> elements() {
        return elements.values();
    }

    @Override
    public Iterable<Element> resolveByName(final String name, final Predicate<Element> filter) {
        final var element = elements.get(name);

        if (element != null && filter.test(element)) {
            return List.of(element);
        }

        return List.of();
    }
}

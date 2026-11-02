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

    /** The types that {@code import module} brought in, by simple name. */
    private final java.util.Map<String, Element> moduleElements = new java.util.HashMap<>();
    /** Simple names that two modules imports bring in as different types, with those types. */
    private final java.util.Map<String, java.util.TreeSet<String>> ambiguous = new java.util.TreeMap<>();
    private final java.util.Set<String> usedAmbiguous = new java.util.TreeSet<>();

    @Override
    public void define(final Element element) {
        if (element != null) {
            elements.put(element.getSimpleName(), element);
        }
    }

    /**
     * Defines a type that a module import ({@code import module m;}) brings in. Another type with the same simple
     * name from a module import makes the name ambiguous (JLS 7.5.5), which is an error if the name is used.
     */
    public void defineFromModule(final Element element) {
        if (element == null) {
            return;
        }
        final var name = element.getSimpleName();
        final var previous = moduleElements.get(name);
        if (previous != null && !qualifiedName(previous).equals(qualifiedName(element))) {
            final var types = ambiguous.computeIfAbsent(name, key -> new java.util.TreeSet<>());
            types.add(qualifiedName(previous));
            types.add(qualifiedName(element));
        }
        moduleElements.put(name, element);
        define(element);
    }

    private static String qualifiedName(final Element element) {
        return element instanceof io.github.potjerodekool.nabu.lang.model.element.TypeElement typeElement
                ? typeElement.getQualifiedName()
                : element.getSimpleName();
    }

    /**
     * @return Returns a message for every ambiguous name that was resolved, for example
     * {@code Reference to 'Date' is ambiguous, both java.sql.Date and java.util.Date match}.
     */
    public java.util.List<String> ambiguityMessages() {
        return usedAmbiguous.stream()
                .map(name -> {
                    final var types = new java.util.ArrayList<>(ambiguous.get(name));
                    return "Reference to '" + name + "' is ambiguous, both "
                            + String.join(" and ", types.subList(0, 2)) + " match";
                })
                .toList();
    }

    @Override
    public Element resolve(final String name) {
        return resolveTypeElement(name)
                .orElse(null);
    }

    private java.util.Optional<Element> resolveTypeElement(final String name) {
        if (ambiguous.containsKey(name)) {
            usedAmbiguous.add(name);
        }
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
                .map(Element::asType)
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

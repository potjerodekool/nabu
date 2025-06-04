package io.github.potjerodekool.nabu.compiler.resolve.scope;

import io.github.potjerodekool.nabu.compiler.ast.element.Element;
import io.github.potjerodekool.nabu.compiler.ast.element.ElementFilter;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;

public class WritableScope implements Scope {

    private final List<Element> elements = new LinkedList<>();

    public WritableScope() {
        super();
    }

    public boolean isEmpty() {
        return this.elements.isEmpty();
    }

    @Override
    public Collection<? extends Element> elements() {
        return this.elements;
    }

    @Override
    public void define(final Element element) {
        this.elements.add(element);
    }

    public void define(final int index,
                       final Element element) {
        this.elements.add(element);
    }

    public void remove(final Element element) {
        this.elements.remove(element);
    }

    @Override
    public Element resolve(final String name) {
        if (!name.contains("$")) {
            return resolveByName(name);
        } else {
            final var names = name.split("\\$");
            final var firstName = names[0];
            final var first = resolveByName(firstName);

            if (first == null) {
                return null;
            }

            return findElementIn(names, 1, first);
        }
    }

    //Temporary hack
    public Element resolveElement(final String name) {
        return elements.stream()
                .filter(it -> it.getSimpleName().equals(name))
                .findFirst()
                .orElse(null);
    }

    private Element resolveByName(final String name) {
        final var list = new ArrayList<>(this.elements);
        return ElementFilter.typesIn(list).stream()
                .filter(it -> it.getSimpleName().equals(name))
                .findFirst()
                .orElse(null);
    }

    private Element findElementIn(final String[] names,
                                  final int index,
                                  final Element searchElement) {
        if (index < names.length) {
            final var name = names[index];
            final var resultOptional = ElementFilter.typesIn(searchElement.getEnclosedElements()).stream()
                    .filter(it -> it.getSimpleName().equals(name))
                    .findFirst();

            if (resultOptional.isEmpty()) {
                return null;
            } else {
                if (index + 1 < names.length) {
                    return findElementIn(names, index + 1, resultOptional.get());
                } else {
                    return null;
                }
            }
        } else {
            return null;
        }
    }

    @Override
    public TypeMirror resolveType(final String name) {
        return ElementFilter.typesIn(elements).stream()
                .filter(it -> it.getSimpleName().equals(name))
                .map(Element::asType)
                .findFirst()
                .orElse(null);
    }
}

package io.github.potjerodekool.nabu.compiler.resolve.scope;

import io.github.potjerodekool.nabu.compiler.ast.element.Element;
import io.github.potjerodekool.nabu.compiler.ast.element.QualifiedNameable;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class ImportScope implements Scope {

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
}

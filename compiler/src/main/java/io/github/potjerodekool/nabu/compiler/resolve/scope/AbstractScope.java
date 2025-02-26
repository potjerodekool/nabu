package io.github.potjerodekool.nabu.compiler.resolve.scope;

import io.github.potjerodekool.nabu.compiler.ast.element.Element;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public abstract class AbstractScope implements Scope {

    private final Map<String, Element> locals = new LinkedHashMap<>();

    private final Scope parent;

    public AbstractScope(final Scope parent) {
        this.parent = parent;
    }

    @Override
    public void define(final Element element) {
        Objects.requireNonNull(element);
        locals.put(element.getSimpleName(), element);
    }

    @Override
    public Set<String> locals() {
        return locals.keySet();
    }

    @Override
    public Scope getParent() {
        return parent;
    }

    @Override
    public Element resolve(final String name) {
        var element = this.locals.get(name);

        if (element != null) {
            return element;
        }

        if (parent != null) {
            return parent.resolve(name);
        }

        return null;
    }
}

package io.github.potjerodekool.nabu.compiler.resolve.scope;

import io.github.potjerodekool.nabu.compiler.ast.element.Element;

public class SwitchScope implements Scope {

    private final Element selectorElement;
    private final Scope parent;

    public SwitchScope(final Element selectorElement, final Scope parent) {
        this.selectorElement = selectorElement;
        this.parent = parent;
    }

    @Override
    public Scope getParent() {
        return parent;
    }

    public Element getSelectorElement() {
        return selectorElement;
    }

    @Override
    public void define(final Element element) {
        final var parent = getParent();

        if (parent != null) {
            parent.define(element);
        }
    }

}

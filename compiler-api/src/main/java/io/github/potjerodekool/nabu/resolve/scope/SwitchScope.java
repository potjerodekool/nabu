package io.github.potjerodekool.nabu.resolve.scope;

import io.github.potjerodekool.nabu.lang.model.element.Element;

/**
 * A scope to resolve in a switch.
 */
public class SwitchScope implements Scope {

    private final Element selectorElement;
    private final Scope parent;

    public SwitchScope(final Element selectorElement,
                       final Scope parent) {
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

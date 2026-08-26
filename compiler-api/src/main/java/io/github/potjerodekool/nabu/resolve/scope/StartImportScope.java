package io.github.potjerodekool.nabu.resolve.scope;

import io.github.potjerodekool.nabu.lang.model.element.Element;

/**
 * Scope for start imports.
 */
public class StartImportScope extends ImportScope {

    @Override
    public void define(final Element element) {
    }

    @Override
    public Element resolve(final String name) {
        return null;
    }
}

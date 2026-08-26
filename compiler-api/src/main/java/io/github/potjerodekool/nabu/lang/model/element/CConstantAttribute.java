package io.github.potjerodekool.nabu.lang.model.element;

import io.github.potjerodekool.nabu.type.TypeMirror;

/**
 * Implementation of {@link ConstantAttribute}
 */
public final class CConstantAttribute extends AbstractAttribute implements ConstantAttribute {

    private final Object value;

    public CConstantAttribute(final Object value) {
        this.value = value;
    }

    @Override
    public Object getValue() {
        return value;
    }

    @Override
    public TypeMirror getType() {
        return null;
    }
}

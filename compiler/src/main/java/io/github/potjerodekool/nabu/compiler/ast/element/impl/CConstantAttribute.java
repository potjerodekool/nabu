package io.github.potjerodekool.nabu.compiler.ast.element.impl;

import io.github.potjerodekool.nabu.compiler.ast.element.ConstantAttribute;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;

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

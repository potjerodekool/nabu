package io.github.potjerodekool.nabu.compiler.ast.element.impl;

public final class ConstantAttribute implements io.github.potjerodekool.nabu.compiler.ast.element.ConstantAttribute {

    private final Object value;

    public ConstantAttribute(final Object value) {
        this.value = value;
    }

    @Override
    public Object getValue() {
        return value;
    }

}

package io.github.potjerodekool.nabu.lang.model.element;

import io.github.potjerodekool.nabu.type.DeclaredType;

public final class CEnumAttributeProxy extends AbstractAttribute implements EnumAttribute {

    private final DeclaredType type;
    private final VariableElement value;

    public CEnumAttributeProxy(final VariableElement variableElement) {
        this.type = (DeclaredType) variableElement.asType();
        this.value = variableElement;
    }

    public DeclaredType getType() {
        return type;
    }

    @Override
    public VariableElement getValue() {
        return value;
    }
}

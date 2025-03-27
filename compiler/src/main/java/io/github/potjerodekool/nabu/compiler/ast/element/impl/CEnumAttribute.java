package io.github.potjerodekool.nabu.compiler.ast.element.impl;

import io.github.potjerodekool.nabu.compiler.ast.element.EnumAttribute;
import io.github.potjerodekool.nabu.compiler.ast.element.VariableElement;
import io.github.potjerodekool.nabu.compiler.type.DeclaredType;

public final class CEnumAttribute extends AbstractAttribute implements EnumAttribute {

    private final DeclaredType type;
    private final VariableElement value;

    public CEnumAttribute(final DeclaredType type,
                          final VariableElement value) {
        this.type = type;
        this.value = value;
    }

    public DeclaredType getType() {
        return type;
    }

    @Override
    public VariableElement getValue() {
        return value;
    }
}

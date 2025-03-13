package io.github.potjerodekool.nabu.compiler.ast.element.impl;

import io.github.potjerodekool.nabu.compiler.ast.element.VariableElement;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.VariableBuilder;
import io.github.potjerodekool.nabu.compiler.type.DeclaredType;

public final class EnumAttributeProxy implements io.github.potjerodekool.nabu.compiler.ast.element.EnumAttribute {

    private final DeclaredType type;
    private final VariableElement value;

    public EnumAttributeProxy(final DeclaredType type,
                              final String name) {
        this.type = type;
        this.value = new VariableBuilder()
                .name(name)
                .type(type)
                .build();
    }

    public DeclaredType getType() {
        return type;
    }

    @Override
    public VariableElement getValue() {
        return value;
    }
}

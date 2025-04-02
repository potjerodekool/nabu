package io.github.potjerodekool.nabu.compiler.ast.element.impl;

import io.github.potjerodekool.nabu.compiler.ast.element.EnumAttribute;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.VariableSymbolBuilderImpl;
import io.github.potjerodekool.nabu.compiler.ast.symbol.VariableSymbol;
import io.github.potjerodekool.nabu.compiler.type.DeclaredType;

public final class CEnumAttributeProxy extends AbstractAttribute implements EnumAttribute {

    private final DeclaredType type;
    private final VariableSymbol value;

    public CEnumAttributeProxy(final DeclaredType type,
                               final String name) {
        this.type = type;
        this.value = new VariableSymbolBuilderImpl()
                .name(name)
                .type(type)
                .build();
    }

    public DeclaredType getType() {
        return type;
    }

    @Override
    public VariableSymbol getValue() {
        return value;
    }
}

package io.github.potjerodekool.nabu.compiler.ast.element.builder.impl;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.TypeVariableSymbol;

public class TypeVariableSymbolBuilder extends AbstractSymbolBuilder<TypeVariableSymbolBuilder> {

    @Override
    protected TypeVariableSymbolBuilder self() {
        return this;
    }

    @Override
    public TypeVariableSymbol build() {
        return new TypeVariableSymbol(this);
    }
}

package io.github.potjerodekool.nabu.compiler.ast.element.builder.impl;

import io.github.potjerodekool.nabu.compiler.ast.symbol.VariableSymbol;

public final class SymbolBuilders {

    private SymbolBuilders() {
    }

    public static <E extends VariableSymbol, EB extends VariableSymbolBuilder<E, EB>> VariableSymbolBuilder<E, EB> variableSymbolBuilder() {
        return (EB) new VariableSymbolBuilderImpl<VariableSymbol>();
    }
}

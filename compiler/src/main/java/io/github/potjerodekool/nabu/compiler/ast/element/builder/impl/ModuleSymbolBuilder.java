package io.github.potjerodekool.nabu.compiler.ast.element.builder.impl;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ModuleSymbol;

public class ModuleSymbolBuilder extends AbstractSymbolBuilder<ModuleSymbolBuilder> {

    @Override
    protected ModuleSymbolBuilder self() {
        return this;
    }

    @Override
    public ModuleSymbol build() {
        return new ModuleSymbol(
                flags,
                simpleName
        );
    }
}

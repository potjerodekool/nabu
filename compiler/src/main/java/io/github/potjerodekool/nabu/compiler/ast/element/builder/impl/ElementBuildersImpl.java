package io.github.potjerodekool.nabu.compiler.ast.element.builder.impl;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ErrorSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.VariableSymbol;
import io.github.potjerodekool.nabu.lang.model.element.TypeElement;
import io.github.potjerodekool.nabu.lang.model.element.VariableElement;
import io.github.potjerodekool.nabu.lang.model.element.builder.ElementBuilders;
import io.github.potjerodekool.nabu.lang.model.element.builder.VariableElementBuilder;

public final class ElementBuildersImpl implements ElementBuilders {

    private static final ElementBuildersImpl INSTANCE = new ElementBuildersImpl();

    public static ElementBuilders getInstance() {
        return INSTANCE;
    }

    private ElementBuildersImpl() {
    }

    public <EB extends VariableElementBuilder<EB>> EB variableElementBuilder() {
        return (EB) new VariableSymbolBuilderImpl();
    }

    public <EB extends VariableElementBuilder<EB>> EB variableElementBuilder(final VariableElement variableElement) {
        return (EB) new VariableSymbolBuilderImpl((VariableSymbol) variableElement);
    }

    @Override
    public TypeElement createErrorSymbol(final String name) {
        return new ErrorSymbol(name);
    }


}



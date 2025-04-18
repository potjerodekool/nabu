package io.github.potjerodekool.nabu.compiler.ast.element.builder;

import io.github.potjerodekool.nabu.compiler.ast.element.VariableElement;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.VariableSymbolBuilderImpl;
import io.github.potjerodekool.nabu.compiler.ast.symbol.VariableSymbol;

public final class ElementBuilders {

    private ElementBuilders() {
    }

    public static <EB extends VariableElementBuilder<EB>> EB variableElementBuilder() {
        return (EB) new VariableSymbolBuilderImpl();
    }

    public static <EB extends VariableElementBuilder<EB>> EB variableElementBuilder(final VariableElement variableElement) {
        return (EB) new VariableSymbolBuilderImpl((VariableSymbol) variableElement);
    }


}



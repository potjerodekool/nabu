package io.github.potjerodekool.nabu.compiler.ast.element.builder;

import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.VariableSymbolBuilderImpl;

public final class ElementBuilders {

    private ElementBuilders() {
    }

    public static <EB extends VariableElementBuilder<EB>> EB variableElementBuilder() {
        return (EB) new VariableSymbolBuilderImpl();
    }


}



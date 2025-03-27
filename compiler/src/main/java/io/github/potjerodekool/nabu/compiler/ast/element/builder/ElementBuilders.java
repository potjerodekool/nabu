package io.github.potjerodekool.nabu.compiler.ast.element.builder;

import io.github.potjerodekool.nabu.compiler.ast.element.VariableElement;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.VariableSymbolBuilderImpl;

public final class ElementBuilders {

    private ElementBuilders() {
    }

    public static <E extends VariableElement, EB extends  VariableElementBuilder<E, EB>> EB variableElementBuilder() {
        return (EB) new VariableSymbolBuilderImpl<>();
    }


}



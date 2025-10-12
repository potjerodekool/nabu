package io.github.potjerodekool.nabu.lang.model.element.builder;

import io.github.potjerodekool.nabu.lang.model.element.TypeElement;
import io.github.potjerodekool.nabu.lang.model.element.VariableElement;

public interface ElementBuilders {

    <EB extends VariableElementBuilder<EB>> EB variableElementBuilder();

    <EB extends VariableElementBuilder<EB>> EB variableElementBuilder(VariableElement variableElement);

    TypeElement createErrorSymbol(String name);
}

package io.github.potjerodekool.nabu.lang.model.element.builder;

import io.github.potjerodekool.nabu.lang.model.element.TypeElement;
import io.github.potjerodekool.nabu.lang.model.element.VariableElement;

/**
 * Utilities for building variables.
 */
public interface ElementBuilders {

    VariableElementBuilder<? extends VariableElement> variableElementBuilder();

    VariableElementBuilder<? extends VariableElement> variableElementBuilder(VariableElement variableElement);

    TypeElement createErrorSymbol(String name);
}

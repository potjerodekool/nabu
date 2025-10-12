package io.github.potjerodekool.nabu.lang.model.element;

import io.github.potjerodekool.nabu.lang.model.element.builder.VariableElementBuilder;

public interface VariableElement extends Element {

    Object getConstantValue();

    default boolean isUnnamed() { return getSimpleName().isEmpty(); }

    <EB extends VariableElementBuilder<EB>> EB builder();
}

package io.github.potjerodekool.nabu.compiler.ast.element;

import io.github.potjerodekool.nabu.compiler.ast.element.builder.VariableElementBuilder;

public interface VariableElement extends Element {

    Object getConstantValue();

    default boolean isUnnamed() { return getSimpleName().isEmpty(); }

    <EB extends VariableElementBuilder<EB>> EB builder();
}

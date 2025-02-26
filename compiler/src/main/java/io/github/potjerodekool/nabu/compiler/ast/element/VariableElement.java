package io.github.potjerodekool.nabu.compiler.ast.element;

public interface VariableElement extends Element {

    Object getConstantValue();

    default boolean isUnnamed() { return getSimpleName().isEmpty(); }

}

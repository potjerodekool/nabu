package io.github.potjerodekool.nabu.compiler.ast.element.builder.impl;

import io.github.potjerodekool.nabu.compiler.ast.element.VariableElement;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.VariableElementBuilder;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;

public interface VariableSymbolBuilder<E extends VariableElement, EB extends VariableSymbolBuilder<E, EB>>
        extends VariableElementBuilder<E, EB>, SymbolBuilder<E, EB> {

    VariableSymbolBuilder<E, EB> type(TypeMirror type);

    VariableSymbolBuilder<E, EB> constantValue(Object constantValue);

    E build();


}

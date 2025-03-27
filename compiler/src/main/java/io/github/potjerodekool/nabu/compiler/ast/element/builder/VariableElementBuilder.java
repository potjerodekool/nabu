package io.github.potjerodekool.nabu.compiler.ast.element.builder;

import io.github.potjerodekool.nabu.compiler.ast.element.VariableElement;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;

public interface VariableElementBuilder<E extends VariableElement, EB extends VariableElementBuilder<E, EB>>
        extends ElementBuilder<E, EB> {

    VariableElementBuilder<E, EB> type(TypeMirror type);

    VariableElementBuilder<E, EB> constantValue(Object constantValue);

}

package io.github.potjerodekool.nabu.lang.model.element.builder;

import io.github.potjerodekool.nabu.lang.model.element.VariableElement;
import io.github.potjerodekool.nabu.type.TypeMirror;

public interface VariableElementBuilder<EB extends VariableElementBuilder<EB>>
        extends ElementBuilder<VariableElement, EB> {

    VariableElementBuilder<EB> type(TypeMirror type);

    VariableElementBuilder<EB> constantValue(Object constantValue);

}

package io.github.potjerodekool.nabu.compiler.ast.element.builder;

import io.github.potjerodekool.nabu.compiler.ast.element.VariableElement;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;

public interface VariableElementBuilder<EB extends VariableElementBuilder<EB>>
        extends ElementBuilder<VariableElement, EB> {

    VariableElementBuilder<EB> type(TypeMirror type);

    VariableElementBuilder<EB> constantValue(Object constantValue);

}

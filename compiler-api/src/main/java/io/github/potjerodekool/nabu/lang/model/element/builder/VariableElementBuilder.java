package io.github.potjerodekool.nabu.lang.model.element.builder;

import io.github.potjerodekool.nabu.lang.model.element.VariableElement;
import io.github.potjerodekool.nabu.type.TypeMirror;

/**
 * A builder for variables.
 * @param <E> Type of VariableElement.
 */
public interface VariableElementBuilder<E extends VariableElement>
        extends ElementBuilder<VariableElementBuilder<E>> {

    VariableElementBuilder<E> type(TypeMirror type);

    VariableElementBuilder<E> constantValue(Object constantValue);

    @Override
    E build();
}

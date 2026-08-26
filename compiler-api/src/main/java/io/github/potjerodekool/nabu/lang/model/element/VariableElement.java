package io.github.potjerodekool.nabu.lang.model.element;

import io.github.potjerodekool.nabu.lang.model.element.builder.VariableElementBuilder;

/**
 * A variable element, like fields, local variables and enum constants.
 */
public interface VariableElement extends Element {

    /**
     * @return Return the constant value of null.
     */
    Object getConstantValue();

    /**
     * See {@link Element#builder()}
     */
    @Override
    VariableElementBuilder<? extends VariableElement> builder();
}

package io.github.potjerodekool.nabu.lang.model.element;

import java.util.List;

/**
 * A parameterizable element.
 */
public interface Parameterizable extends Element {

    /**
     * @return Returns the type parameters.
     */
    List<? extends TypeParameterElement> getTypeParameters();
}

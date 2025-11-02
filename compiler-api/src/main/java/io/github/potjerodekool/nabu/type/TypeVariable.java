package io.github.potjerodekool.nabu.type;

import io.github.potjerodekool.nabu.lang.model.element.Element;

/**
 * A type variable type.
 */
public interface TypeVariable extends ReferenceType {

    Element asElement();

    TypeMirror getUpperBound();

    TypeMirror getLowerBound();

    boolean isCaptured();

}

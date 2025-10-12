package io.github.potjerodekool.nabu.type;

import io.github.potjerodekool.nabu.lang.model.element.Element;

public interface TypeVariable extends ReferenceType {

    Element asElement();

    TypeMirror getUpperBound();

    TypeMirror getLowerBound();

    boolean isCaptured();

    @Override
    default boolean isTypeVariable() {
        return true;
    }
}

package io.github.potjerodekool.nabu.compiler.type;

import io.github.potjerodekool.nabu.compiler.ast.element.Element;

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

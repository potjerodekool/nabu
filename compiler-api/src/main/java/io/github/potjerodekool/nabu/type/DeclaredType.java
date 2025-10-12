package io.github.potjerodekool.nabu.type;

import io.github.potjerodekool.nabu.lang.model.element.Element;
import io.github.potjerodekool.nabu.lang.model.element.TypeElement;

import java.util.List;

public interface DeclaredType extends ReferenceType {

    Element asElement();

    TypeMirror getEnclosingType();

    List<? extends TypeMirror> getTypeArguments();

    @Override
    default boolean isDeclaredType() {
        return true;
    }

    @Override
    default TypeElement asTypeElement() {
        return (TypeElement) asElement();
    }
}

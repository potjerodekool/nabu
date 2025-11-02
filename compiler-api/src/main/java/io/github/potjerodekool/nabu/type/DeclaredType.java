package io.github.potjerodekool.nabu.type;

import io.github.potjerodekool.nabu.lang.model.element.Element;

import java.util.List;

/**
 * A declared type, i.e. class, interface, enum, annotation or record.
 */
public interface DeclaredType extends ReferenceType {

    Element asElement();

    TypeMirror getEnclosingType();

    List<? extends TypeMirror> getTypeArguments();

    @Override
    default boolean isDeclaredType() {
        return true;
    }
}

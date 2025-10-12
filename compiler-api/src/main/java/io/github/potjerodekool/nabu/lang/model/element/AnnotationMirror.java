package io.github.potjerodekool.nabu.lang.model.element;

import io.github.potjerodekool.nabu.type.DeclaredType;

import java.util.Map;

public interface AnnotationMirror {

    DeclaredType getAnnotationType();

    Map<? extends ExecutableElement, ? extends AnnotationValue> getElementValues();
}

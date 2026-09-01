package io.github.potjerodekool.nabu.lang.model.element;

import io.github.potjerodekool.nabu.type.DeclaredType;

import java.util.Map;

/**
 * An annotation mirror.
 */
public interface AnnotationMirror {

    /**
     * Returns the annotation type.
     * @return Returns the annotation type
     */
    DeclaredType getAnnotationType();

    /**
     * Returns the values of the annotation.
     * @return Returns the values of the annotation
     */
    Map<? extends ExecutableElement, ? extends AnnotationValue> getElementValues();
}

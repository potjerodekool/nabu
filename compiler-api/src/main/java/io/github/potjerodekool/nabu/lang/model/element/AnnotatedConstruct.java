package io.github.potjerodekool.nabu.lang.model.element;

import java.lang.annotation.Annotation;
import java.util.List;

/**
 * A construct that can be annotated.
 */
public interface AnnotatedConstruct {

    /**
     * @return Return the annotations.
     */
    List<? extends AnnotationMirror> getAnnotationMirrors();

    /**
     * @param annotationType An annotation type.
     * @return Return the annotation of the given type or null.
     * @param <A> The annotation type.
     */
    <A extends Annotation> A getAnnotation(Class<A> annotationType);

    /**
     * @param annotationType An annotation type.
     * @return Returns an array of annotations of the given type or an empty array.
     * @param <A> The annotation type.
     */
    <A extends Annotation> A[] getAnnotationsByType(Class<A> annotationType);
}

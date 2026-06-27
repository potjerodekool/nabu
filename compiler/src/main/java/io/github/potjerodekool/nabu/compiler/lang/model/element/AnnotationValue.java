package io.github.potjerodekool.nabu.compiler.lang.model.element;

/**
 * Root interface for annotation values.
 */
public sealed interface AnnotationValue permits Attribute {

    Object getValue();

    <R, P> R accept(AnnotationValueVisitor<R, P> v, P p);
}

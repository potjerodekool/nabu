package io.github.potjerodekool.nabu.lang.model.element;

/**
 * Root interface for annotation values.
 */
public sealed interface AnnotationValue permits Attribute {

    /**
     * Returns the value
     * @return Returns the value
     */
    Object getValue();

    /**
     * Accepts the visitor.
     * @param v Visitor
     * @param p A parameter
     * @return Returns a result
     * @param <R> Return type
     * @param <P> Parameter type
     */
    <R, P> R accept(AnnotationValueVisitor<R, P> v, P p);
}

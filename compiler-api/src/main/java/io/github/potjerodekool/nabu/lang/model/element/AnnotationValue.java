package io.github.potjerodekool.nabu.lang.model.element;

public interface AnnotationValue {

    Object getValue();

    <R, P> R accept(AnnotationValueVisitor<R, P> v, P p);
}

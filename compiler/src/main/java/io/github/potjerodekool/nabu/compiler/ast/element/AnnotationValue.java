package io.github.potjerodekool.nabu.compiler.ast.element;

public interface AnnotationValue {

    Object getValue();

    <R, P> R accept(AnnotationValueVisitor<R, P> v, P p);
}

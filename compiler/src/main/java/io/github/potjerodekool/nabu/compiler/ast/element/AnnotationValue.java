package io.github.potjerodekool.nabu.compiler.ast.element;

public sealed interface AnnotationValue permits Attribute {

    Object getValue();

    <R, P> R accept(AnnotationValueVisitor<R, P> v, P p);
}

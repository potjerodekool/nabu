package io.github.potjerodekool.nabu.compiler.ast.element;

import io.github.potjerodekool.nabu.compiler.type.TypeMirror;

import java.util.List;

public interface ArrayAttribute extends Attribute {

    TypeMirror getType();

    @Override
    List<? extends AnnotationValue> getValue();

    @Override
    default  <R, P> R accept(final AnnotationValueVisitor<R, P> v, final P p) {
        return v.visitArray(getValue(), p);
    }
}

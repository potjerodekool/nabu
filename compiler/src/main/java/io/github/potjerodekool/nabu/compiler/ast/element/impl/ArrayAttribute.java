package io.github.potjerodekool.nabu.compiler.ast.element.impl;

import io.github.potjerodekool.nabu.compiler.ast.element.AnnotationValue;
import io.github.potjerodekool.nabu.compiler.ast.element.AnnotationValueVisitor;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;

import java.util.ArrayList;
import java.util.List;

public final class ArrayAttribute implements io.github.potjerodekool.nabu.compiler.ast.element.ArrayAttribute {

    private final TypeMirror type;
    private final List<AnnotationValue> values = new ArrayList<>();

    public ArrayAttribute(final TypeMirror type,
                          final List<AnnotationValue> values) {
        this.type = type;
        this.values.addAll(values);
    }

    public TypeMirror getType() {
        return type;
    }

    @Override
    public List<? extends AnnotationValue> getValue() {
        return values;
    }

    @Override
    public <R, P> R accept(final AnnotationValueVisitor<R, P> v, final P p) {
        return v.visitArray(getValue(), p);
    }
}

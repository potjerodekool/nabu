package io.github.potjerodekool.nabu.compiler.ast.element.impl;

import io.github.potjerodekool.nabu.compiler.ast.element.*;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;

import java.util.ArrayList;
import java.util.List;

/**
 * For internal use only.
 */
public final class CArrayAttributeProxy extends AbstractAttribute implements ArrayAttribute {

    private final List<AnnotationValue> values = new ArrayList<>();

    public CArrayAttributeProxy(final List<AnnotationValue> values) {
        this.values.addAll(values);
    }

    public TypeMirror getType() {
        return null;
    }

    @Override
    public List<? extends AnnotationValue> getValue() {
        return values;
    }

    @Override
    public <R, P> R accept(final AnnotationValueVisitor<R, P> v, final P p) {
        return v.visitArray(getValue(), p);
    }

    public void addValue(final Attribute value) {
        this.values.add(value);
    }
}

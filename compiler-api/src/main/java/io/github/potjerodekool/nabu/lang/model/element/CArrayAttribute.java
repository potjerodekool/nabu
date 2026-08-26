package io.github.potjerodekool.nabu.lang.model.element;

import io.github.potjerodekool.nabu.type.TypeMirror;

import java.util.ArrayList;
import java.util.List;

/**
 * An implementation of @{@link ArrayAttribute}
 */
public final class CArrayAttribute extends AbstractAttribute
        implements ArrayAttribute {

    private final TypeMirror type;
    private final List<AnnotationValue> values = new ArrayList<>();

    public CArrayAttribute(final TypeMirror type,
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

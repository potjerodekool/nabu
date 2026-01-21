package io.github.potjerodekool.nabu.lang.model.element;

import io.github.potjerodekool.nabu.type.TypeMirror;

public final class CClassAttribute extends AbstractAttribute implements ClassAttribute {

    private final TypeMirror typeMirror;

    public CClassAttribute(final TypeMirror typeMirror) {
        this.typeMirror = typeMirror;
    }

    @Override
    public TypeMirror getType() {
        return null;
    }

    @Override
    public TypeMirror getValue() {
        return typeMirror;
    }

    @Override
    public <R, P> R accept(final AnnotationValueVisitor<R, P> v, final P p) {
        return v.visitType(typeMirror, p);
    }
}

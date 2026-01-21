package io.github.potjerodekool.nabu.compiler.annotation.processing.java.element;

import io.github.potjerodekool.nabu.compiler.annotation.processing.java.type.TypeWrapperFactory;
import io.github.potjerodekool.nabu.lang.model.element.ClassAttribute;

import javax.lang.model.element.AnnotationValueVisitor;
import javax.lang.model.type.TypeMirror;

public class JClassAttribute extends JAttribute {

    private final ClassAttribute original;
    private final TypeMirror typeMirror;

    public JClassAttribute(final ClassAttribute original) {
        this.original = original;
        this.typeMirror = TypeWrapperFactory.wrap((io.github.potjerodekool.nabu.type.TypeMirror) original.getValue());
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

package io.github.potjerodekool.nabu.compiler.ast.element;

import io.github.potjerodekool.nabu.compiler.type.DeclaredType;

/**
 * Annotation attribute
 */
public non-sealed interface CompoundAttribute extends AnnotationMirror, Attribute{

    @Override
    default Object getValue() {
        return this;
    }

    @Override
    default  <R, P> R accept(final AnnotationValueVisitor<R, P> v, final P p) {
        return v.visitAnnotation(this, p);
    }

    @Override
    DeclaredType getType();
}

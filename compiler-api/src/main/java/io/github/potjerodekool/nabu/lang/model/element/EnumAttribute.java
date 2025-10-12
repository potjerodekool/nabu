package io.github.potjerodekool.nabu.lang.model.element;

import io.github.potjerodekool.nabu.type.DeclaredType;

public interface EnumAttribute extends Attribute {

    DeclaredType getType();

    @Override
    VariableElement getValue();

    @Override
    default  <R, P> R accept(final AnnotationValueVisitor<R, P> v, final P p) {
        return v.visitEnumConstant(getValue(), p);
    }
}

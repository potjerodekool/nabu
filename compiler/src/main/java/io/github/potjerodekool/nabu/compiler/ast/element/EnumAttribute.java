package io.github.potjerodekool.nabu.compiler.ast.element;

import io.github.potjerodekool.nabu.compiler.type.DeclaredType;

public non-sealed interface EnumAttribute extends Attribute {

    DeclaredType getType();

    @Override
    VariableElement getValue();

    @Override
    default  <R, P> R accept(final AnnotationValueVisitor<R, P> v, final P p) {
        return v.visitEnumConstant(getValue(), p);
    }
}

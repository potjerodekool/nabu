package io.github.potjerodekool.nabu.compiler.annotation.processing.java.element;

import io.github.potjerodekool.nabu.lang.model.element.EnumAttribute;

import javax.lang.model.element.AnnotationValueVisitor;
import javax.lang.model.element.VariableElement;

public class JEnumAttribute extends JAttribute {

    private final EnumAttribute original;
    private VariableElement value;

    public JEnumAttribute(final EnumAttribute original) {
        this.original = original;
    }

    @Override
    public VariableElement getValue() {
        if (value == null) {
            value = (VariableElement) ElementWrapperFactory.wrap(original.getValue());
        }
        return value;
    }

    @Override
    public <R, P> R accept(final AnnotationValueVisitor<R, P> v, final P p) {
        return v.visitEnumConstant(getValue(), p);
    }
}

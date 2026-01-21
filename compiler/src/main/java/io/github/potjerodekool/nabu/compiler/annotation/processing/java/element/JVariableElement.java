package io.github.potjerodekool.nabu.compiler.annotation.processing.java.element;

import io.github.potjerodekool.nabu.lang.model.element.VariableElement;

import javax.lang.model.element.ElementVisitor;

public class JVariableElement extends JElement<VariableElement> implements javax.lang.model.element.VariableElement {

    protected JVariableElement(final VariableElement original) {
        super(original);
    }

    @Override
    public <R, P> R accept(final ElementVisitor<R, P> v, final P p) {
        return v.visitVariable(this, p);
    }

    @Override
    public Object getConstantValue() {
        return getOriginal().getConstantValue();
    }
}

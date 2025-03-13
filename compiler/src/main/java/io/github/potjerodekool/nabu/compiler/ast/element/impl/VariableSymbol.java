package io.github.potjerodekool.nabu.compiler.ast.element.impl;

import io.github.potjerodekool.nabu.compiler.ast.element.*;

public class VariableSymbol extends Symbol implements VariableElement {

    private final Object constantValue;

    public VariableSymbol(final ElementKind kind,
                          final long flags,
                          final String name,
                          final Element owner,
                          final Object constantValue) {
        super(kind, flags, name, owner);
        this.constantValue = constantValue;
    }

    @Override
    public <R, P> R accept(final ElementVisitor<R, P> v, final P p) {
        return v.visitVariable(this, p);
    }

    @Override
    public <R, P> R accept(final SymbolVisitor<R, P> v, final P p) {
        return v.visitVariable(this, p);
    }

    @Override
    public Object getConstantValue() {
        return constantValue;
    }

    @Override
    public boolean isUnnamed() {
        return false;
    }
}

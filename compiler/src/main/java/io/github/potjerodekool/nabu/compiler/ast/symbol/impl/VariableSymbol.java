package io.github.potjerodekool.nabu.compiler.ast.symbol.impl;

import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.VariableSymbolBuilderImpl;
import io.github.potjerodekool.nabu.compiler.type.impl.AbstractType;
import io.github.potjerodekool.nabu.lang.model.element.ElementKind;
import io.github.potjerodekool.nabu.lang.model.element.ElementVisitor;
import io.github.potjerodekool.nabu.lang.model.element.VariableElement;
import io.github.potjerodekool.nabu.type.TypeMirror;

public class VariableSymbol extends Symbol implements VariableElement {

    private final Object constantValue;

    public VariableSymbol(final ElementKind kind,
                          final long flags,
                          final String name,
                          final TypeMirror type,
                          final Symbol owner,
                          final Object constantValue) {
        super(kind, flags, name, (AbstractType) type, owner);
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

    @Override
    public VariableSymbolBuilderImpl builder() {
        return new VariableSymbolBuilderImpl(this);
    }
}

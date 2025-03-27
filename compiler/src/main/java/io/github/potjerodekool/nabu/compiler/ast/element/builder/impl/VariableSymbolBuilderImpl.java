package io.github.potjerodekool.nabu.compiler.ast.element.builder.impl;

import io.github.potjerodekool.nabu.compiler.ast.element.ElementKind;
import io.github.potjerodekool.nabu.compiler.ast.element.VariableElement;
import io.github.potjerodekool.nabu.compiler.ast.symbol.Symbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.VariableSymbol;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;

public class VariableSymbolBuilderImpl<VE extends VariableElement> extends AbstractSymbolBuilder<VE, VariableSymbolBuilderImpl<VE>>
        implements
        VariableSymbolBuilder<VE, VariableSymbolBuilderImpl<VE>>
{

    private TypeMirror type;

    private Object constantValue;

    public VariableSymbolBuilderImpl(final VariableSymbol variableElement) {
        super(variableElement);
        this.type = variableElement.asType();
        this.constantValue = variableElement.getConstantValue();
    }

    public VariableSymbolBuilderImpl() {
        kind = ElementKind.LOCAL_VARIABLE;
    }

    @Override
    protected VariableSymbolBuilderImpl<VE> self() {
        return this;
    }

    @Override
    public VariableSymbolBuilderImpl<VE> type(final TypeMirror type) {
        this.type = type;
        return this;
    }

    @Override
    public VariableSymbolBuilderImpl<VE> constantValue(final Object constantValue) {
        this.constantValue = constantValue;
        return this;
    }

    @Override
    public VE build() {
        return (VE) new VariableSymbol(
                kind,
                getFlags(),
                name,
                type,
                (Symbol) getEnclosingElement(),
                constantValue
        );
    }
}

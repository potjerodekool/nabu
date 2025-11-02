package io.github.potjerodekool.nabu.compiler.ast.element.builder.impl;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.Symbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.VariableSymbol;
import io.github.potjerodekool.nabu.lang.model.element.ElementKind;
import io.github.potjerodekool.nabu.lang.model.element.builder.VariableElementBuilder;
import io.github.potjerodekool.nabu.type.TypeMirror;

public class VariableSymbolBuilderImpl extends AbstractSymbolBuilder<VariableElementBuilder<VariableSymbol>>
        implements VariableElementBuilder<VariableSymbol> {

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
    protected VariableSymbolBuilderImpl self() {
        return this;
    }

    @Override
    public VariableSymbolBuilderImpl type(final TypeMirror type) {
        this.type = type;
        return this;
    }

    @Override
    public VariableSymbolBuilderImpl constantValue(final Object constantValue) {
        this.constantValue = constantValue;
        return this;
    }

    @Override
    public VariableSymbol build() {
        return new VariableSymbol(
                kind,
                getFlags(),
                simpleName,
                type,
                (Symbol) getEnclosingElement(),
                constantValue
        );
    }
}

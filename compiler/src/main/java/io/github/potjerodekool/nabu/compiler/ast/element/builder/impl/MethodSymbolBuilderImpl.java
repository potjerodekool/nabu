package io.github.potjerodekool.nabu.compiler.ast.element.builder.impl;

import io.github.potjerodekool.nabu.compiler.ast.element.TypeParameterElement;
import io.github.potjerodekool.nabu.compiler.ast.element.VariableElement;
import io.github.potjerodekool.nabu.compiler.ast.symbol.MethodSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.Symbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.VariableSymbol;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;

import java.util.ArrayList;
import java.util.List;

public class MethodSymbolBuilderImpl extends AbstractSymbolBuilder<Symbol, MethodSymbolBuilderImpl> implements MethodSymbolBuilder<MethodSymbolBuilderImpl> {

    private final List<VariableElement> parameters = new ArrayList<>();
    private TypeMirror returnType;
    private final List<TypeParameterElement> typeParameters = new ArrayList<>();
    private final List<TypeMirror> argumentTypes = new ArrayList<>();
    private final List<TypeMirror> thrownTypes = new ArrayList<>();
    private TypeMirror receiverType;

    @Override
    protected MethodSymbolBuilderImpl self() {
        return this;
    }

    @Override
    public MethodSymbolBuilderImpl parameter(final VariableSymbol parameter) {
        parameters.add(parameter);
        return this;
    }

    @Override
    public MethodSymbolBuilderImpl returnType(final TypeMirror returnType) {
        this.returnType = returnType;
        return this;
    }

    @Override
    public MethodSymbolBuilderImpl typeParameter(final TypeParameterElement typeParameterElement) {
        typeParameters.add(typeParameterElement);
        return this;
    }

    @Override
    public MethodSymbolBuilderImpl typeParameters(final List<TypeParameterElement> typeParameterElements) {
        this.typeParameters.clear();
        this.typeParameters.addAll(typeParameterElements);
        return this;
    }

    @Override
    public MethodSymbolBuilderImpl argumentType(final TypeMirror argumentType) {
        argumentTypes.add(argumentType);
        return this;
    }

    @Override
    public MethodSymbolBuilderImpl argumentTypes(final List<TypeMirror> argumentTypes) {
        this.argumentTypes.clear();
        this.argumentTypes.addAll(argumentTypes);
        return this;
    }

    @Override
    public MethodSymbolBuilderImpl argumentTypes(final TypeMirror... argumentTypes) {
        return argumentTypes(List.of(argumentTypes));
    }

    @Override
    public MethodSymbolBuilderImpl thrownType(final TypeMirror thrownType) {
        thrownTypes.add(thrownType);
        return this;
    }

    @Override
    public MethodSymbolBuilderImpl thrownTypes(final List<TypeMirror> thrownTypes) {
        this.thrownTypes.clear();
        this.thrownTypes.addAll(thrownTypes);
        return this;
    }

    @Override
    public MethodSymbolBuilderImpl receiverType(final TypeMirror receiverType) {
        this.receiverType = receiverType;
        return this;
    }

    @Override
    public MethodSymbol build() {
        return new MethodSymbol(
                kind,
                getFlags(),
                name,
                (Symbol) getEnclosingElement(),
                receiverType,
                typeParameters,
                returnType,
                argumentTypes,
                thrownTypes,
                parameters,
                annotations
        );
    }
}

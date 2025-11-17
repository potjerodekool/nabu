package io.github.potjerodekool.nabu.compiler.ast.element.builder.impl;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.MethodSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.Symbol;
import io.github.potjerodekool.nabu.lang.model.element.TypeParameterElement;
import io.github.potjerodekool.nabu.lang.model.element.VariableElement;
import io.github.potjerodekool.nabu.lang.model.element.builder.ExecutableElementBuilder;
import io.github.potjerodekool.nabu.type.TypeMirror;

import java.util.ArrayList;
import java.util.List;

public class MethodSymbolBuilderImpl extends AbstractSymbolBuilder<ExecutableElementBuilder<MethodSymbol>> implements ExecutableElementBuilder<MethodSymbol> {

    private final List<VariableElement> parameters = new ArrayList<>();
    private TypeMirror returnType;
    private final List<TypeParameterElement> typeParameters = new ArrayList<>();
    private final List<TypeMirror> thrownTypes = new ArrayList<>();
    private TypeMirror receiverType;

    public MethodSymbolBuilderImpl() {
    }

    public MethodSymbolBuilderImpl(final MethodSymbol original) {
        this.parameters.addAll(original.getParameters());
        this.returnType = original.getReturnType();
        this.typeParameters.addAll(original.getTypeParameters());
        this.thrownTypes.addAll(original.getThrownTypes());
        this.receiverType = original.getReceiverType();
    }

    @Override
    protected MethodSymbolBuilderImpl self() {
        return this;
    }

    @Override
    public MethodSymbolBuilderImpl parameter(final VariableElement parameter) {
        this.parameters.add(parameter);
        return this;
    }

    @Override
    public MethodSymbolBuilderImpl parameters(final List<VariableElement> parameters) {
        this.parameters.addAll(parameters);
        return this;
    }

    @Override
    public MethodSymbolBuilderImpl returnType(final TypeMirror returnType) {
        this.returnType = returnType;
        return this;
    }

    @Override
    public MethodSymbolBuilderImpl typeParameter(final TypeParameterElement typeParameterElement) {
        this.typeParameters.add(typeParameterElement);
        return this;
    }

    @Override
    public MethodSymbolBuilderImpl typeParameters(final List<TypeParameterElement> typeParameterElements) {
        this.typeParameters.clear();
        this.typeParameters.addAll(typeParameterElements);
        return this;
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

    public MethodSymbolBuilderImpl receiverType(final TypeMirror receiverType) {
        this.receiverType = receiverType;
        return this;
    }

    public MethodSymbol build() {
        return new MethodSymbol(
                kind,
                getFlags(),
                simpleName,
                (Symbol) getEnclosingElement(),
                receiverType,
                typeParameters,
                returnType,
                thrownTypes,
                parameters,
                annotations
        );
    }
}

package io.github.potjerodekool.nabu.compiler.ast.element.impl;

import io.github.potjerodekool.nabu.compiler.TodoException;
import io.github.potjerodekool.nabu.compiler.ast.element.*;
import io.github.potjerodekool.nabu.compiler.type.ExecutableType;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;
import io.github.potjerodekool.nabu.compiler.type.TypeVariable;
import io.github.potjerodekool.nabu.compiler.type.impl.CMethodType;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class MethodSymbol extends AbstractSymbol implements ExecutableElement {
    private final List<VariableElement> parameters = new ArrayList<>();
    private final List<TypeParameterElement> typeParameters = new ArrayList<>();

    public MethodSymbol(final ElementKind kind,
                        final Set<Modifier> modifiers,
                        final String name,
                        final Element owner,
                        final List<TypeParameterElement> typeParameters,
                        final TypeMirror returnType,
                        final List<TypeMirror> argumentTypes,
                        final List<TypeMirror> thrownTypes) {
        super(kind, modifiers, name, owner);
        this.typeParameters.addAll(typeParameters);
        final var methodType = new CMethodType(
                this,
                typeParameters.stream()
                        .map(it -> (TypeVariable) it.asType())
                        .toList(),
                returnType,
                argumentTypes,
                thrownTypes
        );
        setType(methodType);
    }

    @Override
    public ExecutableType asType() {
        return (ExecutableType) super.asType();
    }

    @Override
    public TypeMirror getReturnType() {
        return asType().getReturnType();
    }

    public List<VariableElement> getParameters() {
        return parameters;
    }

    @Override
    public TypeMirror getReceiverType() {
        throw new TodoException();
    }

    @Override
    public boolean isVarArgs() {
        throw new TodoException();
    }

    @Override
    public boolean isDefault() {
        throw new TodoException();
    }

    @Override
    public List<? extends TypeMirror> getThrownTypes() {
        return List.of();
    }

    @Override
    public AnnotationValue getDefaultValue() {
        throw new TodoException();
    }

    public void addParameter(final VariableElement parameter) {
        this.parameters.add(parameter);
    }

    public List<? extends TypeParameterElement> getTypeParameters() {
        return typeParameters;
    }

    @Override
    public <R, P> R accept(final ElementVisitor<R, P> v, final P p) {
        return v.visitExecutableElement(this, p);
    }

}

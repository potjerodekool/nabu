package io.github.potjerodekool.nabu.compiler.ast.element.impl;

import io.github.potjerodekool.nabu.compiler.Flags;
import io.github.potjerodekool.nabu.compiler.TodoException;
import io.github.potjerodekool.nabu.compiler.ast.element.*;
import io.github.potjerodekool.nabu.compiler.type.ExecutableType;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;
import io.github.potjerodekool.nabu.compiler.type.TypeVariable;
import io.github.potjerodekool.nabu.compiler.type.impl.CMethodType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class MethodSymbol extends Symbol implements ExecutableElement {
    private final List<VariableElement> parameters = new ArrayList<>();
    private final List<TypeParameterElement> typeParameters = new ArrayList<>();
    private final boolean isDefaultMethod;
    private final boolean isVarArgs;
    private AnnotationValue defaultValue;

    public MethodSymbol(final ElementKind kind,
                        final long flags,
                        final String name,
                        final Element owner,
                        final List<TypeParameterElement> typeParameters,
                        final TypeMirror returnType,
                        final List<TypeMirror> argumentTypes,
                        final List<TypeMirror> thrownTypes,
                        final List<VariableElement> parameters,
                        final boolean isVarArgs,
                        final List<AnnotationMirror> annotations) {
        super(kind, flags, name, owner);
        this.typeParameters.addAll(typeParameters);
        this.setAnnotations(annotations);
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

        parameters.forEach(Objects::requireNonNull);

        this.parameters.addAll(parameters);
        this.isDefaultMethod = isDefaultMethod(owner);
        this.isVarArgs = isVarArgs;
    }

    private boolean isDefaultMethod(final Element owner) {
        return owner instanceof TypeElement typeElement
                && typeElement.getKind() == ElementKind.INTERFACE
                && !Flags.hasFlag(getFlags(), Flags.ABSTRACT);
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
        return isVarArgs;
    }

    @Override
    public boolean isDefault() {
        return isDefaultMethod;
    }

    @Override
    public List<? extends TypeMirror> getThrownTypes() {
        return List.of();
    }

    @Override
    public AnnotationValue getDefaultValue() {
        return this.defaultValue;
    }

    public void setDefaultValue(final AnnotationValue defaultValue) {
        this.defaultValue = defaultValue;
    }

    public void addParameter(final VariableElement parameter) {
        Objects.requireNonNull(parameter);
        this.parameters.add(parameter);
    }

    public List<? extends TypeParameterElement> getTypeParameters() {
        return typeParameters;
    }

    @Override
    public <R, P> R accept(final ElementVisitor<R, P> v, final P p) {
        return v.visitExecutable(this, p);
    }

    @Override
    public <R, P> R accept(final SymbolVisitor<R, P> v, final P p) {
        return v.visitMethod(this, p);
    }
}

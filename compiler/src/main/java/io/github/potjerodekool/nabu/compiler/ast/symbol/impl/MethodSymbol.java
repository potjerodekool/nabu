package io.github.potjerodekool.nabu.compiler.ast.symbol.impl;

import io.github.potjerodekool.nabu.compiler.ast.symbol.builder.impl.MethodSymbolBuilderImpl;
import io.github.potjerodekool.nabu.compiler.type.impl.CMethodType;
import io.github.potjerodekool.nabu.lang.Flags;
import io.github.potjerodekool.nabu.lang.model.element.*;
import io.github.potjerodekool.nabu.type.TypeMirror;
import io.github.potjerodekool.nabu.type.TypeVariable;
import io.github.potjerodekool.nabu.util.Types;

import java.util.ArrayList;
import java.util.List;

public class MethodSymbol extends Symbol implements ExecutableElement {
    private final List<VariableElement> parameters = new ArrayList<>();
    private final List<TypeParameterElement> typeParameters = new ArrayList<>();
    private final boolean isDefaultMethod;
    private AnnotationValue defaultValue;

    public MethodSymbol(final ElementKind kind,
                        final long flags,
                        final String name,
                        final Symbol owner,
                        final TypeMirror receiverType,
                        final List<TypeParameterElement> typeParameters,
                        final TypeMirror returnType,
                        final List<TypeMirror> thrownTypes,
                        final List<VariableElement> parameters,
                        final List<AnnotationMirror> annotations) {
        super(kind, flags, name, null, owner);
        this.typeParameters.addAll(typeParameters);
        this.setAnnotations(annotations);
        final var methodType = new CMethodType(
                this,
                receiverType,
                typeParameters.stream()
                        .map(it -> (TypeVariable) it.asType())
                        .toList(),
                returnType,
                List.of(),
                thrownTypes
        );
        setType(methodType);

        parameters.forEach(this::addParameter);

        this.isDefaultMethod = isDefaultMethod(owner);
    }

    private boolean isDefaultMethod(final Element owner) {
        return owner instanceof TypeElement typeElement
                && typeElement.getKind() == ElementKind.INTERFACE
                && !Flags.hasFlag(getFlags(), Flags.ABSTRACT);
    }

    @Override
    public CMethodType asType() {
        return (CMethodType) super.asType();
    }

    @Override
    public TypeMirror getReturnType() {
        return asType().getReturnType();
    }

    public void setReturnType(final TypeMirror returnType) {
        asType().setReturnType(returnType);
    }

    public List<VariableElement> getParameters() {
        return parameters;
    }

    @Override
    public TypeMirror getReceiverType() {
        return asType().getReceiverType();
    }

    @Override
    public boolean isVarArgs() {
        if (hasFlag(Flags.VARARGS)) {
            return true;
        }

        return parameters.stream()
                .filter(p -> p.asType() instanceof io.github.potjerodekool.nabu.compiler.type.impl.CArrayType)
                .anyMatch(p -> ((io.github.potjerodekool.nabu.compiler.type.impl.CArrayType) p.asType()).isVarArgs());
    }

    @Override
    public boolean isDefault() {
        return isDefaultMethod;
    }

    @Override
    public List<? extends TypeMirror> getThrownTypes() {
        return asType().getThrownTypes();
    }

    public void setThrownTypes(final List<TypeMirror> thrownTypes) {
        asType().setThrownTypes(thrownTypes);
    }

    @Override
    public AnnotationValue getDefaultValue() {
        return this.defaultValue;
    }

    public void setDefaultValue(final AnnotationValue defaultValue) {
        this.defaultValue = defaultValue;
    }

    public void addParameter(final VariableElement parameter) {
        this.parameters.forEach(p -> {
            if (p.getSimpleName().equals(parameter.getSimpleName())) {
                throw new IllegalArgumentException();
            }
        });

        this.parameters.add(parameter);
        asType().addParameterType(parameter.asType());
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

    public MethodSymbolBuilderImpl builder() {
        return new MethodSymbolBuilderImpl(this);
    }

    @Override
    public ModuleElement getModuleElement() {
        final var enclosingElement = getEnclosingElement();
        return enclosingElement != null ? enclosingElement.getModuleElement() : null;
    }


    public boolean overrides(final ExecutableElement overridden,
                             final TypeElement type,
                             final Types types,
                             final boolean b) {
        return false;
    }
}

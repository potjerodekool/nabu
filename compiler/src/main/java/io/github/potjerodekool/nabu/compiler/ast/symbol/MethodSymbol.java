package io.github.potjerodekool.nabu.compiler.ast.symbol;

import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.MethodSymbolBuilderImpl;
import io.github.potjerodekool.nabu.compiler.internal.Flags;
import io.github.potjerodekool.nabu.compiler.ast.element.*;
import io.github.potjerodekool.nabu.compiler.backend.ir.ProcFrag;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;
import io.github.potjerodekool.nabu.compiler.type.TypeVariable;
import io.github.potjerodekool.nabu.compiler.type.impl.CMethodType;

import java.util.ArrayList;
import java.util.List;

public class MethodSymbol extends Symbol implements ExecutableElement {
    private final List<VariableSymbol> parameters = new ArrayList<>();
    private final List<TypeParameterElement> typeParameters = new ArrayList<>();
    private final boolean isDefaultMethod;
    private AnnotationValue defaultValue;
    private ProcFrag frag;

    public MethodSymbol(final ElementKind kind,
                        final long flags,
                        final String name,
                        final Symbol owner,
                        final TypeMirror receiverType,
                        final List<TypeParameterElement> typeParameters,
                        final TypeMirror returnType,
                        final List<TypeMirror> thrownTypes,
                        final List<VariableSymbol> parameters,
                        final List<AnnotationMirror> annotations) {
        super(kind, flags, name, null, owner);
        this.typeParameters.addAll(typeParameters);

        final var parameterTypes = parameters.stream()
                .map(Symbol::asType)
                .toList();

        this.setAnnotations(annotations);
        final var methodType = new CMethodType(
                this,
                receiverType,
                typeParameters.stream()
                        .map(it -> (TypeVariable) it.asType())
                        .toList(),
                returnType,
                parameterTypes,
                thrownTypes
        );
        setType(methodType);

        parameters.forEach(this::addParameter);

        this.isDefaultMethod = isDefaultMethod(owner);
    }

    public ProcFrag getFrag() {
        return frag;
    }

    public void setFrag(final ProcFrag frag) {
        this.frag = frag;
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

    public List<VariableSymbol> getParameters() {
        return parameters;
    }

    @Override
    public TypeMirror getReceiverType() {
        return asType().getReceiverType();
    }

    @Override
    public boolean isVarArgs() {
        return hasFlag(Flags.VARARGS);
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

    public void addParameter(final VariableSymbol parameter) {
        this.parameters.forEach(p -> {
            if (p.getSimpleName().equals(parameter.getSimpleName())) {
                throw new IllegalArgumentException();
            }
        });

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

    public MethodSymbolBuilderImpl builder() {
        return new MethodSymbolBuilderImpl(this);
    }
}

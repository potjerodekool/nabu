package io.github.potjerodekool.nabu.compiler.ast.element.builder;

import io.github.potjerodekool.nabu.compiler.ast.element.*;
import io.github.potjerodekool.nabu.compiler.ast.element.impl.MethodSymbol;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class MethodBuilder extends AbstractElementBuilder<MethodBuilder> {

    private final List<VariableElement> parameters = new ArrayList<>();
    private TypeMirror returnType;
    private final List<TypeParameterElement> typeParameters = new ArrayList<>();
    private final List<TypeMirror> argumentTypes = new ArrayList<>();
    private final List<TypeMirror> thrownTypes = new ArrayList<>();
    private boolean isVarArgs;

    public MethodBuilder() {
        kind = ElementKind.METHOD;
    }

    public MethodBuilder parameter(final Consumer<VariableBuilder> consumer) {
        final var builder = new VariableBuilder();
        builder.kind(ElementKind.PARAMETER);
        consumer.accept(builder);
        parameters.add(builder.build());
        return this;
    }

    public MethodBuilder returnType(final TypeMirror returnType) {
        this.returnType = returnType;
        return this;
    }

    public MethodBuilder typeParameter(final TypeParameterElement typeParameterElement) {
        typeParameters.add(typeParameterElement);
        return this;
    }

    public MethodBuilder typeParameters(final List<TypeParameterElement> typeParameterElements) {
        typeParameters.addAll(typeParameterElements);
        return this;
    }

    public MethodBuilder argumentType(final TypeMirror argumentType) {
        argumentTypes.add(argumentType);
        return this;
    }

    public MethodBuilder argumentTypes(final List<TypeMirror> argumentTypes) {
        this.argumentTypes.addAll(argumentTypes);
        return this;
    }

    public MethodBuilder argumentTypes(final TypeMirror... argumentTypes) {
        this.argumentTypes.addAll(List.of(argumentTypes));
        return this;
    }

    public MethodBuilder thrownType(final TypeMirror thrownType) {
        thrownTypes.add(thrownType);
        return this;
    }

    public MethodBuilder thrownTypes(final List<TypeMirror> thrownTypes) {
        this.thrownTypes.addAll(thrownTypes);
        return this;
    }

    public MethodBuilder varArgs(final boolean isVarArgs) {
        this.isVarArgs = isVarArgs;
        return this;
    }

    public ExecutableElement build() {
        return new MethodSymbol(
                kind,
                getFlags(),
                name,
                enclosing,
                typeParameters,
                returnType,
                argumentTypes,
                thrownTypes,
                parameters,
                isVarArgs,
                annotations
        );
    }

    @Override
    protected MethodBuilder self() {
        return this;
    }
}

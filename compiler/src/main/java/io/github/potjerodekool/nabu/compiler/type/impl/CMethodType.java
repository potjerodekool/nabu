package io.github.potjerodekool.nabu.compiler.type.impl;

import io.github.potjerodekool.nabu.compiler.ast.element.ExecutableElement;
import io.github.potjerodekool.nabu.compiler.ast.element.TypeElement;
import io.github.potjerodekool.nabu.compiler.type.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class CMethodType extends AbstractType implements ExecutableType {

    private final TypeMirror receiverType;

    private TypeMirror returnType;

    private List<TypeMirror> parameterTypes = new ArrayList<>();

    private final List<TypeVariable> typeVariables = new ArrayList<>();

    private final List<TypeMirror> thrownTypes = new ArrayList<>();

    private final ExecutableElement methodSymbol;

    public CMethodType(final ExecutableElement methodSymbol,
                       final TypeMirror receiverType,
                       final List<? extends TypeVariable> typeVariables,
                       final TypeMirror returnType,
                       final List<? extends TypeMirror> parameterTypes,
                       final List<? extends TypeMirror> thrownTypes) {
        super(null);
        this.methodSymbol = methodSymbol;
        this.receiverType = receiverType;
        this.typeVariables.addAll(typeVariables);
        this.returnType = returnType;
        this.parameterTypes.addAll(parameterTypes);
        this.thrownTypes.addAll(thrownTypes);
        validateTypes(thrownTypes);
    }

    private void validateTypes(final List<? extends TypeMirror> thrownTypes) {
        thrownTypes.forEach(Objects::requireNonNull);
    }

    @Override
    public List<? extends TypeMirror> getParameterTypes() {
        return parameterTypes;
    }

    public void addParameterType(final TypeMirror parameterType) {
        this.parameterTypes.add(parameterType);
    }

    public void setParameterTypes(final List<TypeMirror> parameterTypes) {
        this.parameterTypes = parameterTypes;
    }

    @Override
    public List<? extends TypeVariable> getTypeVariables() {
        return typeVariables;
    }

    @Override
    public TypeElement getOwner() {
        return (TypeElement) methodSymbol.getEnclosingElement();
    }

    @Override
    public ExecutableElement getMethodSymbol() {
        return methodSymbol;
    }

    @Override
    public TypeKind getKind() {
        return TypeKind.EXECUTABLE;
    }

    @Override
    public TypeMirror getReturnType() {
        return returnType;
    }

    public void setReturnType(final TypeMirror returnType) {
        this.returnType = returnType;
    }

    @Override
    public List<? extends TypeMirror> getThrownTypes() {
        return thrownTypes;
    }

    @Override
    public String getClassName() {
        return "methodType";
    }

    @Override
    public TypeMirror getReceiverType() {
        return receiverType;
    }

    @Override
    public <R, P> R accept(final TypeVisitor<R, P> visitor,
                           final P param) {
        return visitor.visitMethodType(this, param);
    }

    @Override
    public boolean equals(final Object o) {
        if (!(o instanceof final CMethodType that)) {
            return false;
        }
        return Objects.equals(receiverType, that.receiverType)
                && Objects.equals(returnType, that.returnType)
                && Objects.equals(parameterTypes, that.parameterTypes)
                && Objects.equals(typeVariables, that.typeVariables)
                && Objects.equals(thrownTypes, that.thrownTypes)
                && Objects.equals(methodSymbol, that.methodSymbol);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                receiverType,
                returnType,
                parameterTypes,
                typeVariables,
                thrownTypes,
                methodSymbol);
    }
}

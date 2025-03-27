package io.github.potjerodekool.nabu.compiler.type.impl;

import io.github.potjerodekool.nabu.compiler.ast.element.ExecutableElement;
import io.github.potjerodekool.nabu.compiler.ast.element.TypeElement;
import io.github.potjerodekool.nabu.compiler.type.*;

import java.util.ArrayList;
import java.util.List;

public class CMethodType extends AbstractType implements ExecutableType {

    private final TypeMirror receiverType;

    private final TypeMirror returnType;

    private final List<TypeMirror> argumentTypes = new ArrayList<>();

    private final List<TypeVariable> typeVariables = new ArrayList<>();

    private final List<TypeMirror> thrownTypes = new ArrayList<>();

    private final ExecutableElement methodSymbol;

    public CMethodType(final ExecutableElement methodSymbol,
                       final TypeMirror receiverType,
                       final List<? extends TypeVariable> typeVariables,
                       final TypeMirror returnType,
                       final List<? extends TypeMirror> argumentTypes,
                       final List<? extends TypeMirror> thrownTypes) {
        super(null);
        this.methodSymbol = methodSymbol;
        this.receiverType = receiverType;
        this.typeVariables.addAll(typeVariables);
        this.returnType = returnType;
        this.argumentTypes.addAll(argumentTypes);
        this.thrownTypes.addAll(thrownTypes);
    }

    @Override
    public List<? extends TypeMirror> getParameterTypes() {
        return argumentTypes;
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
}

package io.github.potjerodekool.nabu.compiler.type.mutable;

import io.github.potjerodekool.nabu.compiler.ast.element.MethodSymbol;
import io.github.potjerodekool.nabu.compiler.ast.element.TypeElement;
import io.github.potjerodekool.nabu.compiler.type.MethodType;
import io.github.potjerodekool.nabu.compiler.type.TypeKind;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;
import io.github.potjerodekool.nabu.compiler.type.TypeVisitor;

import java.util.ArrayList;
import java.util.List;

public class MutableMethodType implements MethodType {

    private TypeMirror returnType;

    private final List<TypeMirror> argumentTypes = new ArrayList<>();

    private TypeElement owner;

    private final MethodSymbol methodSymbol;

    public MutableMethodType(final MethodSymbol methodSymbol) {
        this(null, methodSymbol, null, null);
    }

    public MutableMethodType(final TypeElement owner,
                             final MethodSymbol methodSymbol,
                             final TypeMirror returnType,
                             final List<TypeMirror> argumentTypes) {
        this.owner = owner;
        this.methodSymbol = methodSymbol;
        this.returnType = returnType;

        if (argumentTypes != null) {
            this.argumentTypes.addAll(argumentTypes);
        }
    }

    @Override
    public TypeElement getOwner() {
        return owner;
    }


    public void setOwner(final TypeElement owner) {
        this.owner = owner;
    }

    @Override
    public MethodSymbol getMethodSymbol() {
        return methodSymbol;
    }

    @Override
    public TypeKind getKind() {
        return TypeKind.METHOD;
    }

    @Override
    public List<TypeMirror> getArgumentTypes() {
        return argumentTypes;
    }

    @Override
    public TypeMirror getReturnType() {
        return returnType;
    }

    public void setReturnType(final TypeMirror returnType) {
        this.returnType = returnType;
    }

    @Override
    public <R, P> R accept(final TypeVisitor<R, P> visitor,
                           final P param) {
        return visitor.visitMethodType(this, param);
    }
}

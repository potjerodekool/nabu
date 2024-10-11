package io.github.potjerodekool.nabu.compiler.type.mutable;

import io.github.potjerodekool.nabu.compiler.type.TypeKind;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;
import io.github.potjerodekool.nabu.compiler.type.TypeVisitor;
import io.github.potjerodekool.nabu.compiler.type.VariableType;

public class MutableVariableType implements VariableType {

    private TypeMirror interferedType;

    public MutableVariableType() {
    }

    public MutableVariableType(final TypeMirror interferedType) {
        this.interferedType = interferedType;
    }

    @Override
    public TypeKind getKind() {
        return null;
    }

    @Override
    public <R, P> R accept(final TypeVisitor<R, P> visitor, final P param) {
        return visitor.visitVariableType(this, param);
    }

    @Override
    public TypeMirror getInterferedType() {
        return interferedType;
    }

    public void setInterferedType(final TypeMirror interferedType) {
        this.interferedType = interferedType;
    }
}

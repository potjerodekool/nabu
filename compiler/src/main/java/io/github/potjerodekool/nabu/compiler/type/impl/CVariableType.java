package io.github.potjerodekool.nabu.compiler.type.impl;

import io.github.potjerodekool.nabu.compiler.type.TypeKind;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;
import io.github.potjerodekool.nabu.compiler.type.TypeVisitor;
import io.github.potjerodekool.nabu.compiler.type.VariableType;

public class CVariableType extends AbstractType implements VariableType {

    private TypeMirror interferedType;

    public CVariableType() {
    }

    public CVariableType(final TypeMirror interferedType) {
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
        if (interferedType != null) {
            this.interferedType = interferedType;
        }
    }

    @Override
    public String toString() {
        if (interferedType != null) {
            return interferedType.toString();
        } else {
            return "var";
        }
    }
}

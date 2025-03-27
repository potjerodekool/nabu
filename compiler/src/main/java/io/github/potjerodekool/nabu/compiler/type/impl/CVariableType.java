package io.github.potjerodekool.nabu.compiler.type.impl;

import io.github.potjerodekool.nabu.compiler.type.TypeKind;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;
import io.github.potjerodekool.nabu.compiler.type.TypeVisitor;
import io.github.potjerodekool.nabu.compiler.type.VariableType;

public class CVariableType extends AbstractType implements VariableType {

    private final TypeMirror interferedType;

    public CVariableType(final TypeMirror interferedType) {
        super(null);
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
    public String getClassName() {
        return interferedType != null
                ? interferedType.getClassName()
                : "var";
    }

    @Override
    public TypeMirror getInterferedType() {
        return interferedType;
    }

    @Override
    public String toString() {
        return getClassName();
    }
}

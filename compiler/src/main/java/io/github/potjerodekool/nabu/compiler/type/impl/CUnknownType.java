package io.github.potjerodekool.nabu.compiler.type.impl;

import io.github.potjerodekool.nabu.type.TypeKind;
import io.github.potjerodekool.nabu.type.TypeVisitor;

public class CUnknownType extends AbstractType {

    public CUnknownType() {
        super(null);
    }

    @Override
    public TypeKind getKind() {
        return TypeKind.ERROR;
    }

    @Override
    public <R, P> R accept(final TypeVisitor<R, P> visitor, final P param) {
        return visitor.visitUnknownType(this, param);
    }

    @Override
    public boolean equals(final Object obj) {
        return false;
    }

    @Override
    public String getClassName() {
        return "";
    }

    @Override
    public int hashCode() {
        return 0;
    }
}

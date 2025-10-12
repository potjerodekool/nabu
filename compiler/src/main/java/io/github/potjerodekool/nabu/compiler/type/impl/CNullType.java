package io.github.potjerodekool.nabu.compiler.type.impl;

import io.github.potjerodekool.nabu.type.NullType;
import io.github.potjerodekool.nabu.type.TypeKind;
import io.github.potjerodekool.nabu.type.TypeVisitor;

public class CNullType extends AbstractType implements NullType {

    public CNullType() {
        super(null);
    }

    @Override
    public TypeKind getKind() {
        return TypeKind.NULL;
    }

    @Override
    public <R, P> R accept(final TypeVisitor<R, P> visitor, final P param) {
        return visitor.visitNullType(this, param);
    }

    @Override
    public String getClassName() {
        return "null";
    }

    @Override
    public boolean equals(final Object obj) {
        return obj instanceof CNullType;
    }

    @Override
    public int hashCode() {
        return 32;
    }
}

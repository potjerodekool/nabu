package io.github.potjerodekool.nabu.compiler.type.impl;

import io.github.potjerodekool.nabu.compiler.type.NoType;
import io.github.potjerodekool.nabu.compiler.type.TypeKind;
import io.github.potjerodekool.nabu.compiler.type.TypeVisitor;

public class CNoType extends AbstractType implements NoType {

    public CNoType() {
        super(null);
    }

    @Override
    public TypeKind getKind() {
        return TypeKind.NONE;
    }

    @Override
    public <R, P> R accept(final TypeVisitor<R, P> visitor, final P param) {
        return visitor.visitNoneType(this, param);
    }

    @Override
    public String getClassName() {
        return "NO";
    }

    @Override
    public boolean equals(final Object obj) {
        return obj instanceof CNoType;
    }

    @Override
    public int hashCode() {
        return 32;
    }
}

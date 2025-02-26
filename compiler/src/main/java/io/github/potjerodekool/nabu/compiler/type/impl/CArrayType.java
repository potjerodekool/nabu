package io.github.potjerodekool.nabu.compiler.type.impl;

import io.github.potjerodekool.nabu.compiler.type.*;

public class CArrayType extends AbstractType implements ArrayType {

    private final TypeMirror componentType;

    public CArrayType(final TypeMirror componentType) {
        this.componentType = componentType;
    }

    @Override
    public TypeKind getKind() {
        return TypeKind.ARRAY;
    }

    @Override
    public <R, P> R accept(final TypeVisitor<R, P> visitor,
                           final P param) {
        return visitor.visitArrayType(this, param);
    }

    @Override
    public TypeMirror getComponentType() {
        return componentType;
    }

    @Override
    public boolean equals(final Object obj) {
        if (obj instanceof ArrayType otherArrayType) {
            return componentType.equals(otherArrayType.getComponentType());
        }
        return false;
    }
}

package io.github.potjerodekool.nabu.compiler.type.immutable;

import io.github.potjerodekool.nabu.compiler.type.ArrayType;
import io.github.potjerodekool.nabu.compiler.type.TypeKind;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;
import io.github.potjerodekool.nabu.compiler.type.TypeVisitor;

public class ImmutableArrayType implements ArrayType {

    private final TypeMirror componentType;

    public ImmutableArrayType(final TypeMirror componentType) {
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
}

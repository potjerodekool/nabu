package io.github.potjerodekool.nabu.compiler.type.immutable;

import io.github.potjerodekool.nabu.compiler.type.TypeKind;
import io.github.potjerodekool.nabu.compiler.type.TypeVisitor;
import io.github.potjerodekool.nabu.compiler.type.VoidType;

public class ImmutableVoidType implements VoidType {

    @Override
    public TypeKind getKind() {
        return TypeKind.VOID;
    }

    @Override
    public <R, P> R accept(final TypeVisitor<R, P> visitor,
                           final P param) {
        return visitor.visitVoidType(this , param);
    }
}

package io.github.potjerodekool.nabu.compiler.type.immutable;

import io.github.potjerodekool.nabu.compiler.type.NullType;
import io.github.potjerodekool.nabu.compiler.type.TypeKind;
import io.github.potjerodekool.nabu.compiler.type.TypeVisitor;

public class ImmutableNullType implements NullType {

    @Override
    public TypeKind getKind() {
        return TypeKind.NULL;
    }

    @Override
    public <R, P> R accept(final TypeVisitor<R, P> visitor, final P param) {
        return visitor.visitNullType(this, param);
    }
}

package io.github.potjerodekool.nabu.compiler.type.immutable;

import io.github.potjerodekool.nabu.compiler.type.PrimitiveType;
import io.github.potjerodekool.nabu.compiler.type.TypeKind;
import io.github.potjerodekool.nabu.compiler.type.TypeVisitor;

public class ImmutablePrimitiveType implements PrimitiveType {

    private final TypeKind kind;

    public ImmutablePrimitiveType(final TypeKind kind) {
        this.kind = kind;
    }

    @Override
    public TypeKind getKind() {
        return kind;
    }

    @Override
    public <R, P> R accept(final TypeVisitor<R, P> visitor,
                           final P param) {
        return visitor.visitPrimitiveType(this, param);
    }
}

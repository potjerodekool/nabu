package io.github.potjerodekool.nabu.compiler.type.impl;

import io.github.potjerodekool.nabu.compiler.type.NoType;
import io.github.potjerodekool.nabu.compiler.type.TypeKind;
import io.github.potjerodekool.nabu.compiler.type.TypeVisitor;

public class CVoidType extends AbstractType implements NoType {

    public CVoidType() {
        super(null);
    }

    @Override
    public TypeKind getKind() {
        return TypeKind.VOID;
    }

    @Override
    public <R, P> R accept(final TypeVisitor<R, P> visitor,
                           final P param) {
        return visitor.visitNoType(this , param);
    }

    @Override
    public String getClassName() {
        return "void";
    }
}

package io.github.potjerodekool.nabu.compiler.type.impl;

import io.github.potjerodekool.nabu.type.PrimitiveType;
import io.github.potjerodekool.nabu.type.TypeKind;
import io.github.potjerodekool.nabu.type.TypeVisitor;

public class CPrimitiveType extends AbstractType implements PrimitiveType {

    private final TypeKind kind;

    public CPrimitiveType(final TypeKind kind) {
        super(null);
        if (kind == null || !kind.isPrimitive()) {
            throw new IllegalArgumentException("Not a primitive kind " + kind);
        }
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

    @Override
    public String getClassName() {
        return switch (kind) {
            case BOOLEAN -> "boolean";
            case CHAR -> "char";
            case BYTE -> "byte";
            case SHORT -> "short";
            case INT -> "int";
            case FLOAT -> "float";
            case LONG -> "long";
            case DOUBLE -> "double";
            default -> "";
        };
    }

    @Override
    public String toString() {
        return getClassName();
    }

    @Override
    public boolean isPrimitiveType() {
        return true;
    }

    @Override
    public boolean equals(final Object obj) {
        return obj instanceof CPrimitiveType other
                && getKind() == other.getKind();
    }

    @Override
    public int hashCode() {
        return getKind().hashCode();
    }
}

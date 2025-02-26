package io.github.potjerodekool.nabu.compiler.type.impl;

import io.github.potjerodekool.nabu.compiler.TodoException;
import io.github.potjerodekool.nabu.compiler.type.PrimitiveType;
import io.github.potjerodekool.nabu.compiler.type.TypeKind;
import io.github.potjerodekool.nabu.compiler.type.TypeVisitor;

public class CPrimitiveType extends AbstractType implements PrimitiveType {

    private final TypeKind kind;

    public CPrimitiveType(final TypeKind kind) {
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
    public String toString() {
        return switch (kind) {
            case BOOLEAN -> "boolean";
            case CHAR -> "char";
            case BYTE -> "byte";
            case SHORT -> "short";
            case INT -> "int";
            case FLOAT -> "float";
            case LONG -> "long";
            case DOUBLE -> "double";
            default -> throw new TodoException("");
        };
    }

    @Override
    public boolean isPrimitiveType() {
        return true;
    }
}

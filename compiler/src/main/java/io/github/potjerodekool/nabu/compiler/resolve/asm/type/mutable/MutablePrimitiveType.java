package io.github.potjerodekool.nabu.compiler.resolve.asm.type.mutable;

import io.github.potjerodekool.nabu.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.type.TypeKind;
import io.github.potjerodekool.nabu.type.TypeMirror;
import io.github.potjerodekool.nabu.type.TypeVariable;

import java.util.Map;

public class MutablePrimitiveType extends MutableType {

    private final char descriptor;
    public MutablePrimitiveType(final char descriptor) {
        this.descriptor = descriptor;
    }

    @Override
    public TypeMirror toType(final ClassElementLoader loader, final Map<String, TypeVariable> typeVariablesMap) {
        return switch (descriptor) {
            case 'B' -> loader.getTypes().getPrimitiveType(TypeKind.BYTE);
            case 'C' -> loader.getTypes().getPrimitiveType(TypeKind.CHAR);
            case 'D' -> loader.getTypes().getPrimitiveType(TypeKind.DOUBLE);
            case 'F' -> loader.getTypes().getPrimitiveType(TypeKind.FLOAT);
            case 'I' -> loader.getTypes().getPrimitiveType(TypeKind.INT);
            case 'J' -> loader.getTypes().getPrimitiveType(TypeKind.LONG);
            case 'S' -> loader.getTypes().getPrimitiveType(TypeKind.SHORT);
            case 'V' -> loader.getTypes().getNoType(TypeKind.VOID);
            case 'Z' -> loader.getTypes().getPrimitiveType(TypeKind.BOOLEAN);
            default -> throw new IllegalArgumentException();
        };
    }
}

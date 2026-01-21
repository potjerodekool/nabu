package io.github.potjerodekool.nabu.compiler.resolve.asm.type.mutable;

import io.github.potjerodekool.nabu.type.TypeKind;
import io.github.potjerodekool.nabu.type.TypeMirror;
import io.github.potjerodekool.nabu.type.TypeVariable;
import io.github.potjerodekool.nabu.util.Types;

import java.util.Map;

public class MutablePrimitiveType extends MutableType {

    private final char descriptor;
    public MutablePrimitiveType(final char descriptor) {
        this.descriptor = descriptor;
    }

    @Override
    public TypeMirror toType(final Types types,
                             final Map<String, TypeVariable> typeVariablesMap) {
        return switch (descriptor) {
            case 'B' -> types.getPrimitiveType(TypeKind.BYTE);
            case 'C' -> types.getPrimitiveType(TypeKind.CHAR);
            case 'D' -> types.getPrimitiveType(TypeKind.DOUBLE);
            case 'F' -> types.getPrimitiveType(TypeKind.FLOAT);
            case 'I' -> types.getPrimitiveType(TypeKind.INT);
            case 'J' -> types.getPrimitiveType(TypeKind.LONG);
            case 'S' -> types.getPrimitiveType(TypeKind.SHORT);
            case 'V' -> types.getNoType(TypeKind.VOID);
            case 'Z' -> types.getPrimitiveType(TypeKind.BOOLEAN);
            default -> throw new IllegalArgumentException();
        };
    }
}

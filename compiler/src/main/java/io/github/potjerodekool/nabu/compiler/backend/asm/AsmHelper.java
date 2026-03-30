package io.github.potjerodekool.nabu.compiler.backend.asm;

import io.github.potjerodekool.nabu.ir.types.IRType;
import io.github.potjerodekool.nabu.ir.values.IRValue;
import io.github.potjerodekool.nabu.tools.TodoException;

import java.util.List;
import java.util.stream.Collectors;

public final class AsmHelper {

    private AsmHelper() {
    }

    public static String createDescriptor(final List<IRValue> params,
                                          final IRType returnType,
                                          final AsmContext asmContext) {
        final var paramsPart = params.stream()
                .map(param -> AsmHelper.toInternalName(param, asmContext))
                .collect(Collectors.joining("", "(", ")"));
        final var returnTypePart = toInternalName(returnType);
        return paramsPart + returnTypePart;
    }

    private static String toInternalName(final IRValue value,
                                         final AsmContext asmContext) {
        return switch (value.type()) {
            case IRType.Array arrayType -> "[" + toInternalName(arrayType.elem());
            case IRType.Ptr(IRType.Int pointee) when pointee.bits() == 8 -> {
                yield "Ljava/lang/String;";
            }
            case IRType.Ptr(IRType.Ptr pointee) -> toInternalName(pointee);
            default -> throw new TodoException();
        };
    }

    private static String toInternalName(final IRType type) {
        return switch (type) {
            case IRType.Array arrayType -> "[" + toInternalName(arrayType.elem());
            case IRType.Ptr ptr -> {
                if (ptr.pointee() instanceof IRType.Ptr(IRType pointee)
                        && pointee instanceof IRType.Int(int bits) && bits == 8) {
                    yield "Ljava/lang/String;";
                } else if (ptr.pointee() instanceof IRType.Int intType && intType.bits() == 8) {
                    yield "[Ljava/lang/String;";
                } else {
                    yield "Ljava/lang/Object;";
                }
            }
            case IRType.Void ignored -> "V";
            case IRType.Float floatType when floatType.bits() == 32 -> "F";
            case IRType.Float floatType when floatType.bits() == 64 -> "D";
            case IRType.Bool ignored -> "Z";
            case IRType.Int intType when intType.bits() == 32 -> "I";
            case IRType.Int intType when intType.bits() == 64 -> "L";
            default -> throw new TodoException();
        };
    }
}

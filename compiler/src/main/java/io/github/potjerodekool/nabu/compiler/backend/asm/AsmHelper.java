package io.github.potjerodekool.nabu.compiler.backend.asm;

import io.github.potjerodekool.nabu.compiler.ir.types.IRType;
import io.github.potjerodekool.nabu.compiler.ir.values.IRValue;

import java.util.List;
import java.util.stream.Collectors;

public final class AsmHelper {

    private AsmHelper() {
    }

    public static String createDescriptorWithValues(final List<IRValue> params,
                                                    final IRType returnType) {
        final var paramsPart = params.stream()
                .map(param -> AsmHelper.createDescriptor(param.type()))
                .collect(Collectors.joining("", "(", ")"));
        final var returnTypePart = createDescriptor(returnType);
        return paramsPart + returnTypePart;
    }

    public static String createDescriptor(final List<IRType> paramTypes,
                                          final IRType returnType) {
        final var paramsPart = paramTypes.stream()
                .map(AsmHelper::createDescriptor)
                .collect(Collectors.joining("", "(", ")"));
        final var returnTypePart = AsmHelper.createDescriptor(returnType);
        return paramsPart + returnTypePart;
    }

    public static String toInternalName(final IRType type) {
        return switch (type) {
            case IRType.Array arrayType -> "[" + toInternalName(arrayType.elem());
            case IRType.Ptr ptr -> {
                final var desc = ptr.jvmDescriptor();
                if (desc != null) {
                    yield descriptorToInternalName(desc);
                }
                if (ptr.pointee() instanceof IRType.Int(int bits) && bits == 8) {
                    yield "java/lang/String";
                } else if (ptr.pointee() instanceof IRType.Function) {
                    yield "java/lang/invoke/MethodHandle";
                } else {
                    yield "java/lang/Object";
                }
            }
            case IRType.Void ignored -> "V";
            case IRType.Float floatType when floatType.bits() == 32 -> "F";
            case IRType.Float floatType when floatType.bits() == 64 -> "D";
            case IRType.Bool ignored -> "Z";
            case IRType.Int intType -> switch (intType.bits()) {
                case 8 -> "B";
                case 16 -> "S";
                case 32 -> "I";
                case 64 -> "J";
                default -> "I";
            };
            case IRType.Function ignored -> "java/lang/invoke/MethodHandle";
            default -> throw new UnsupportedOperationException("Unsupported type for internal name: " + type);
        };
    }

    public static String createDescriptor(final IRType type) {
        return switch (type) {
            case IRType.Void ignored1 -> "V";
            case IRType.Int(int bits) -> switch (bits) {
                case 8 -> "B";
                case 16 -> "S";
                case 32 -> "I";
                case 64 -> "J";
                default -> "I";
            };
            case IRType.Bool ignored -> "Z";
            case IRType.Float(int bits) -> bits == 32 ? "F" : "D";
            case IRType.Ptr ptr -> {
                final var desc = ptr.jvmDescriptor();
                if (desc != null) {
                    yield desc;
                }
                if (ptr.pointee() instanceof IRType.Function) {
                    yield "Ljava/lang/invoke/MethodHandle;";
                }
                yield "Ljava/lang/String;";
            }
            case IRType.Array arrayType -> "[" + createDescriptor(arrayType.elem());
            case IRType.Function ignored -> "Ljava/lang/invoke/MethodHandle;";
            default -> throw new UnsupportedOperationException("Unsupported type for descriptor: " + type);
        };
    }

    /**
     * Converteert een JVM descriptor naar een interne naam.
     * "Ljava/lang/String;" → "java/lang/String"
     * "[Ljava/lang/String;" → "[Ljava/lang/String;"
     * "[I" → "[I"
     */
    private static String descriptorToInternalName(final String descriptor) {
        if (descriptor.startsWith("[")) {
            return descriptor;
        }
        if (descriptor.startsWith("L") && descriptor.endsWith(";")) {
            return descriptor.substring(1, descriptor.length() - 1);
        }
        return descriptor;
    }

    public static String toInternalName(final String className) {
        return className.replace('.', '/');
    }
}

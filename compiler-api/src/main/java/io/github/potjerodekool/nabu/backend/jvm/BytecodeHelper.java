package io.github.potjerodekool.nabu.backend.jvm;

import io.github.potjerodekool.nabu.lang.model.element.TypeElement;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;
import io.github.potjerodekool.nabu.type.DeclaredType;
import io.github.potjerodekool.nabu.type.TypeMirror;

import java.util.List;
import java.util.stream.Collectors;

public final class BytecodeHelper {

    private BytecodeHelper() {
    }

    public static String createDescriptorWithValues(final List<IRValue> params,
                                                    final IRType returnType) {
        final var paramsPart = params.stream()
                .map(param -> BytecodeHelper.createDescriptor(param.type()))
                .collect(Collectors.joining("", "(", ")"));
        final var returnTypePart = createDescriptor(returnType);
        return paramsPart + returnTypePart;
    }

    public static String createDescriptor(final List<IRType> paramTypes,
                                          final IRType returnType) {
        final var paramsPart = paramTypes.stream()
                .map(BytecodeHelper::createDescriptor)
                .collect(Collectors.joining("", "(", ")"));
        final var returnTypePart = BytecodeHelper.createDescriptor(returnType);
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
                if (ptr.pointee() instanceof IRType.Int(int bits)) {
                    if (bits == 8) {
                        yield "java/lang/String";
                    } else {
                        yield "java/lang/Object";
                    }
                }
                if (ptr.pointee() instanceof IRType.Function) {
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
                if (ptr.pointee() instanceof IRType.Ptr) {
                    yield "Ljava/lang/String;";
                }
                yield "Ljava/lang/Object;";
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

    /**
     * Converteert een TypeMirror naar een JVM-descriptor.
     * Gebruikt voor annotaties e.d.
     */
    public static String createDescriptor(final TypeMirror type) {
        if (type instanceof DeclaredType declared) {
            final var element = declared.asElement();
            if (element instanceof TypeElement typeElement) {
                return "L" + toInternalName(typeElement.getQualifiedName()) + ";";
            }
        }
        return "Ljava/lang/Object;";
    }
}

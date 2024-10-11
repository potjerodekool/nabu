package io.github.potjerodekool.nabu.compiler.backend.generate;

import io.github.potjerodekool.nabu.compiler.backend.ir.type.IPrimitiveType;
import io.github.potjerodekool.nabu.compiler.backend.ir.type.IReferenceType;
import io.github.potjerodekool.nabu.compiler.backend.ir.type.IType;
import io.github.potjerodekool.nabu.compiler.resolve.ClassUtils;
import org.objectweb.asm.Type;

import java.util.List;
import java.util.stream.Collectors;

public final class AsmUtils {

    private AsmUtils() {
    }

    public static String getMethodDescriptor(final List<IType> parameterTypes,
                                             final IType returnType) {
        final var pTypes = parameterTypes.stream()
                .map(AsmUtils::toAsmType)
                .toArray(Type[]::new);

        final var retType = toAsmType(returnType);
        return Type.getMethodDescriptor(retType, pTypes);
    }

    public static String getMethodSignature(final List<IType> parameterTypes,
                                            final IType returnType) {
        if (parameterTypes.stream()
                .noneMatch(AsmUtils::isGenericType)
            && !isGenericType(returnType)) {
            return null;
        }

        final var params = parameterTypes.stream()
                .map(AsmUtils::getSignature)
                .collect(Collectors.joining("", "(", ")"));

        final var retType = getSignature(returnType);
        return params + retType;
    }

    private static boolean isGenericType(final IType type) {
        if (type instanceof IReferenceType referenceType) {
            return referenceType.getTypeArguments() != null;
        } else {
            return false;
        }
    }


    private static String getSignature(final IType type) {
        if (type instanceof IReferenceType referenceType) {
            final var asmType = toAsmType(referenceType);
            if (referenceType.getTypeArguments() != null) {
                final var typeArgs = referenceType.getTypeArguments().stream()
                        .map(AsmUtils::getSignature)
                        .collect(Collectors.joining("", "<", ">"));
                var name = asmType.getDescriptor();
                name = name.substring(0, name.length() - 1);
                return name + typeArgs + ";";
            } else {
                return asmType.getDescriptor();
            }
        } else if (type instanceof IPrimitiveType primitiveType) {
            return toAsmType(primitiveType).getDescriptor();
        } else {
            throw new UnsupportedOperationException("" + type.getKind());
        }
    }

    public static Type toAsmType(final IType type) {
        if (type == null) {
            return Type.VOID_TYPE;
        } else {
            return switch (type) {
                case IReferenceType referenceType -> {
                    final var name = referenceType.getName();
                    var descriptor = ClassUtils.getClassDescriptor(name);
                    yield Type.getType(descriptor);
                }
                case IPrimitiveType primitiveType ->
                        switch (primitiveType.getKind()) {
                            case CHAR -> Type.CHAR_TYPE;
                            case BYTE -> Type.BYTE_TYPE;
                            case BOOLEAN -> Type.BOOLEAN_TYPE;
                            case INT -> Type.INT_TYPE;
                            case DOUBLE -> Type.DOUBLE_TYPE;
                            case FLOAT -> Type.FLOAT_TYPE;
                            case LONG -> Type.LONG_TYPE;
                            case SHORT -> Type.SHORT_TYPE;
                            case VOID -> Type.VOID_TYPE;
                            default -> throw new IllegalStateException("Invalid primitive type " + primitiveType.getKind().name());
                        };
            };
        }
    }
}

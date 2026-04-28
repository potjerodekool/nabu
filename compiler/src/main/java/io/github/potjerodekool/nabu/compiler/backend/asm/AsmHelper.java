package io.github.potjerodekool.nabu.compiler.backend.asm;

import io.github.potjerodekool.nabu.compiler.backend.ir2.TypeMirrorToIRType;
import io.github.potjerodekool.nabu.ir.types.IRType;
import io.github.potjerodekool.nabu.ir.values.IRValue;
import io.github.potjerodekool.nabu.tools.TodoException;
import io.github.potjerodekool.nabu.type.*;

import java.util.ArrayList;
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

    public static String createDescriptor(final ExecutableType executableType) {
        final var origionalParameterTypes = executableType.getMethodSymbol().asType().getParameterTypes();
        final var paramTypes = executableType.getParameterTypes();

        final var irParamTypes = new ArrayList<IRType>();

        for (var i = 0; i < paramTypes.size(); i++) {
            final var paramType = paramTypes.get(i);
            final var originalParamType = origionalParameterTypes.get(i);
            final IRType irType;

            if (originalParamType instanceof TypeVariable typeVariable) {
                irType = AsmHelper.toIRType(typeVariable.getUpperBound());
            } else {
                irType = AsmHelper.toIRType(paramType);
            }
            irParamTypes.add(irType);
        }

        final TypeMirror returnTypeMirror;

        if (executableType.getMethodSymbol().getReturnType() instanceof TypeVariable typeVariable) {
            returnTypeMirror = typeVariable.getUpperBound();
        } else {
            returnTypeMirror = executableType.getReturnType();
        }

        final var returnType = toIRType(returnTypeMirror);
        return createDescriptor(irParamTypes, returnType);
    }

    private static IRType toIRType(final TypeMirror typeMirror) {
        if (typeMirror.getKind() == TypeKind.TYPEVAR) {
            final var upper = typeMirror.getUpperBound();
            return new IRType.Ptr(IRType.I8, upper);
        }

        return TypeMirrorToIRType.map(typeMirror);
    }

    public static String createDescriptor(final List<IRType> paramTypes,
                                          final IRType returnType) {
        final var paramsPart = paramTypes.stream()
                .map(AsmHelper::createDescriptor)
                .collect(Collectors.joining("", "(", ")"));
        final var returnTypePart = AsmHelper.createDescriptor(returnType);
        return paramsPart + returnTypePart;
    }

    private static String toInternalName(final IRValue value) {
        return switch (value.type()) {
            case IRType.Array arrayType -> "[" + toInternalName(arrayType.elem());
            case IRType.Ptr(IRType.Int pointee, TypeMirror m) when pointee.bits() == 8 -> "Ljava/lang/String;";
            case IRType.Ptr(IRType.Ptr pointee, TypeMirror m) -> {
                //TODO generics
                final var className = m.asTypeElement().getQualifiedName();
                yield "L" + className.replace('.', '/') + ";";
            }
            default -> throw new TodoException();
        };
    }

    public static String toInternalName(final IRType type) {
        return switch (type) {
            case IRType.Array arrayType -> "[" + toInternalName(arrayType.elem());
            case IRType.Ptr ptr -> {
                if (ptr.customType() != null) {
                    final var customType = ptr.customType();
                    yield toInternalName(customType);
                }
                if (ptr.pointee() instanceof IRType.Ptr(IRType pointee, TypeMirror m)
                        && pointee instanceof IRType.Int(int bits) && bits == 8) {
                    yield "java/lang/String";
                } else if (ptr.pointee() instanceof IRType.Int(int bits) && bits == 8) {
                    yield "java/lang/String";
                } else {
                    yield "java/lang/Object";
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

    private static String toInternalName(final TypeMirror typeMirror) {
        if (typeMirror instanceof DeclaredType declaredType) {
            final var qualifiedName = declaredType.asTypeElement().getQualifiedName();
            return qualifiedName.replace('.', '/');
        } else if (typeMirror instanceof ArrayType arrayType) {
            final var componentType = arrayType.getComponentType();
            var componentName = toInternalName(arrayType.getComponentType());

            if (componentType.getKind() == TypeKind.DECLARED) {
                componentName = "L" + componentName + ";";
            }

            return "[" + componentName;
        } else {
            throw new TodoException();
        }
    }

    public static String createDescriptor(final IRType type) {
        return switch (type) {
            case IRType.Void ignored1 -> "V";
            case IRType.Int(int bits) ->
                              bits == 8 ? "B"
                            : bits == 16 ? "S"
                            : bits == 32 ? "I" : "J";
            case IRType.Bool ignored -> "Z";
            case IRType.Float(int bits) -> bits == 32 ? "F" : "D";
            case IRType.Ptr(IRType pointTee, TypeMirror m) -> {
                if (m == null) {
                    yield "Ljava/lang/String;";
                }

                yield createDescriptor(m);
            }
            default -> throw new TodoException();
        };
    }

    private static String createDescriptor(final TypeMirror typeMirror) {
        return switch (typeMirror.getKind()) {
            case DECLARED -> {
                final var className = typeMirror.asTypeElement().getQualifiedName();
                yield "L" + className.replace('.', '/') + ";";
            }
            case ARRAY -> {
                final var arrayType = (ArrayType) typeMirror;
                final var componentType = createDescriptor(arrayType.getComponentType());
                yield "[" + componentType;
            }
            case BYTE -> {
                yield "B";
            }
            default -> throw new TodoException();
        };
    }

    public static String toInternalName(final String className) {
        return className.replace('.', '/');
    }
}

package io.github.potjerodekool.nabu.compiler.backend.generate.asm.signature;

import io.github.potjerodekool.nabu.compiler.TodoException;
import io.github.potjerodekool.nabu.compiler.backend.generate.signature.ISignatureGenerator;
import io.github.potjerodekool.nabu.compiler.backend.ir.type.*;
import io.github.potjerodekool.nabu.compiler.resolve.internal.ClassUtils;
import org.objectweb.asm.Type;

import java.util.List;

public class AsmISignatureGenerator extends ISignatureGenerator {

    public static final AsmISignatureGenerator INSTANCE = new AsmISignatureGenerator();

    private AsmISignatureGenerator() {
    }

    @Override
    public String getDescriptor(final IType type) {
        return toAsmType(type).getDescriptor();
    }

    @Override
    public String getMethodDescriptor(final List<? extends IType> parameterTypes,
                                      final IType returnType) {
        final var pTypes = parameterTypes.stream()
                .map(AsmISignatureGenerator::toAsmType)
                .toArray(Type[]::new);

        final var retType = toAsmType(returnType);
        return Type.getMethodDescriptor(retType, pTypes);
    }

    @Override
    protected String getSignature(final IPrimitiveType primitiveType) {
        return toAsmType(primitiveType).getDescriptor();
    }

    public static Type toAsmType(final IType type) {
        if (type == null) {
            throw new NullPointerException();
        } else {
            return switch (type) {
                case IReferenceType referenceType -> {
                    final var name = referenceType.getName();
                    var descriptor = ClassUtils.getClassDescriptor(name);
                    yield Type.getType(descriptor);
                }
                case IWildcardType ignored -> throw new TodoException();
                case IPrimitiveType primitiveType -> switch (primitiveType.getKind()) {
                    case CHAR -> Type.CHAR_TYPE;
                    case BYTE -> Type.BYTE_TYPE;
                    case BOOLEAN -> Type.BOOLEAN_TYPE;
                    case INT -> Type.INT_TYPE;
                    case DOUBLE -> Type.DOUBLE_TYPE;
                    case FLOAT -> Type.FLOAT_TYPE;
                    case LONG -> Type.LONG_TYPE;
                    case SHORT -> Type.SHORT_TYPE;
                    case VOID -> Type.VOID_TYPE;
                    default ->
                            throw new IllegalStateException("Invalid primitive type " + primitiveType.getKind().name());
                };
                case ITypeVariable typeVariable -> typeVariable.getUpperBound() != null
                        ? toAsmType(typeVariable.getUpperBound())
                        : toAsmType(typeVariable.getLowerBound());
                case IArrayType arrayType -> {
                    final var componentType = toAsmType(arrayType.getComponentType());
                    yield Type.getType("[" + componentType.getDescriptor());
                }
                default -> throw new IllegalArgumentException(type.getClass().getName());
            };
        }
    }
}


package io.github.potjerodekool.nabu.compiler.backend.bytecode;

import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Vertaalt ASM {@link Type} (JVM-descriptors, al volledig geresolved -
 * generics zijn erased, dus dit is eenvoudiger dan de source-AST-route via
 * {@code TypeMirrorToIRType}) naar Nabu's {@link IRType}.
 *
 * <p>Objectreferenties (class- en array-descriptors) worden net als overal
 * elders in de codebase gerepresenteerd als {@code IRType.Ptr(IRType.I8,
 * jvmDescriptor)} - zie {@code TypeMirrorToIRType.map()} voor het
 * DECLARED-geval, dat exact dezelfde vorm gebruikt.
 */
final class DescriptorMapper {

    private DescriptorMapper() {
    }

    static IRType mapType(final Type type) {
        return switch (type.getSort()) {
            case Type.VOID -> IRType.VOID;
            case Type.BOOLEAN -> IRType.BOOL;
            case Type.BYTE -> IRType.I8;
            case Type.CHAR -> IRType.CHAR;
            case Type.SHORT -> IRType.I16;
            case Type.INT -> IRType.I32;
            case Type.LONG -> IRType.I64;
            case Type.FLOAT -> IRType.F32;
            case Type.DOUBLE -> IRType.F64;
            case Type.ARRAY, Type.OBJECT -> new IRType.Ptr(IRType.I8, type.getDescriptor());
            case Type.METHOD -> throw new IllegalArgumentException(
                    "mapType() verwacht geen METHOD-type, gebruik Type.getArgumentTypes()/getReturnType()");
            default -> throw new IllegalArgumentException("Onbekend ASM-type-sort: " + type.getSort());
        };
    }

    /**
     * Elementtype voor de {@code newarray}-opcode, die een {@code int}-
     * operand gebruikt die een van de {@code Opcodes.T_*}-constanten is
     * (anders dan de descriptor-gebaseerde {@code anewarray}).
     */
    static IRType mapNewArrayType(final int atype) {
        return switch (atype) {
            case Opcodes.T_BOOLEAN -> IRType.BOOL;
            case Opcodes.T_CHAR -> IRType.CHAR;
            case Opcodes.T_SHORT -> IRType.I16;
            case Opcodes.T_FLOAT -> IRType.F32;
            case Opcodes.T_DOUBLE -> IRType.F64;
            case Opcodes.T_BYTE -> IRType.I8;
            case Opcodes.T_LONG -> IRType.I64;
            case Opcodes.T_INT -> IRType.I32;
            default -> throw new IllegalArgumentException("Onbekend newarray-atype: " + atype);
        };
    }
}

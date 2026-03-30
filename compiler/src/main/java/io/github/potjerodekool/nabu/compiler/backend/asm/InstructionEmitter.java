package io.github.potjerodekool.nabu.compiler.backend.asm;

import io.github.potjerodekool.nabu.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.ir.types.IRType;
import io.github.potjerodekool.nabu.ir.values.IRValue;
import io.github.potjerodekool.nabu.tools.TodoException;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.HashMap;
import java.util.Map;

public class InstructionEmitter {

    private final MethodVisitor methodVisitor;
    private final ASMByteCodeEmitter codeEmitter;
    private final Map<String, Local> locals = new HashMap<>();
    private int lastIndex = -1;

    public InstructionEmitter(final MethodVisitor methodVisitor,
                              final ASMByteCodeEmitter codeEmitter) {
        this.methodVisitor = methodVisitor;
        this.codeEmitter = codeEmitter;
    }

    public void emit(final IRInstruction instr) {
        switch (instr) {
            case IRInstruction.Return returnInst -> {
                if (returnInst.value() != null) {
                    emit(returnInst.value());
                    final var returnOpcode = resolveReturnOpcode(returnInst.value());

                    methodVisitor.visitInsn(returnOpcode);
                } else {
                    methodVisitor.visitInsn(Opcodes.RETURN);
                }
            }
            case IRInstruction.BinaryOp binaryOp -> {
                emit(binaryOp.left());
                emit(binaryOp.right());
                switch (binaryOp.op()) {
                    case ADD -> {
                        final var opcode = resolveAddOpcode(binaryOp.left());
                        methodVisitor.visitInsn(opcode);
                    }
                    case SUB -> {
                        final var opcode = resolveSubOpcode(binaryOp.left());
                        methodVisitor.visitInsn(opcode);
                    }
                    case MUL -> {
                        final var opcode = resolveMUlOpcode(binaryOp.left());
                        methodVisitor.visitInsn(opcode);
                    }
                    case DIV -> {
                        final var opcode = resolveDivOpcode(binaryOp.left());
                        methodVisitor.visitInsn(opcode);
                    }
                    case MOD -> {
                        final var opcode = resolveModOpcode(binaryOp.left());
                        methodVisitor.visitInsn(opcode);
                    }
                    case EQ -> {
                        final var opcode = resolveEQOpcode(binaryOp.left());
                        visitJump(opcode, binaryOp.result());
                    }
                    case LT -> {
                        final var opcode = resolveLTOpcode(binaryOp.left());
                        visitJump(opcode, binaryOp.result());
                    }
                    case GTE -> {
                        final var opcode = resolveGTEOpode(binaryOp.left());
                        visitJump(opcode, binaryOp.result());
                    }
                    default ->  throw new TodoException("" + binaryOp.op());
                }
            }
            case IRInstruction.Alloca allocaInst -> {
                emitAllocate(allocaInst);
            }
            case IRInstruction.Store store -> {
                visitStore(store);
            }
            case IRInstruction.Load loadInst -> {
                visitLoad(loadInst);
            }
            case IRInstruction.Call call -> {
                emitFunctionCall(call);
            }
            default -> throw new TodoException("" + instr);
        }
    }

    private void emitFunctionCall(final IRInstruction.Call call) {
        call.args().forEach(this::emit);

        final var opcode = Opcodes.INVOKESTATIC;
        final var descriptor = AsmHelper.createDescriptor(
                call.args(),
                call.result().type(),
                this.codeEmitter
        );

        methodVisitor.visitMethodInsn(
                opcode,
                "test",
                call.function(),
                descriptor,
                false
        );
    }

    private void visitLoad(final IRInstruction.Load loadInst) {
        final var opcode = resolveLoadOpcode(loadInst.ptr());

        final var temp = (IRValue.Temp) loadInst.ptr();
        var name = temp.name();
        final var dotIndex = name.indexOf('.');
        name = name.substring(1, dotIndex);
        final var local = locals.get(name);

        methodVisitor.visitVarInsn(
                opcode,
                local.index()
        );
    }

    private int resolveLoadOpcode(final IRValue value) {
        final var temp = (IRValue.Temp) value;
        var name = temp.name();
        final var dotIndex = name.indexOf('.');
        name = name.substring(1, dotIndex);
        final var local = locals.get(name);

        return switch (local.type()) {
            case IRType.Int intType when intType.bits() == 32 -> Opcodes.ILOAD;
            case IRType.Int intType when intType.bits() == 64 -> Opcodes.LLOAD;
                default -> throw new TodoException();
        };

    }

    private void visitStore(final IRInstruction.Store store) {
        final var temp = (IRValue.Temp) store.ptr();
        var name = temp.name();
        final var dotIndex = name.indexOf('.');
        name = name.substring(1, dotIndex);

        final var local = createLocal(name, store.value().type());

        final var varIndex = local.index();
        final var storeOpcode = resolveStoreOpcode(store.value());

        emit(store.value());

        methodVisitor.visitVarInsn(
                storeOpcode,
                varIndex
        );
    }

    private void storeValue() {}


    private void emitAllocate(final IRInstruction.Alloca allocaInst) {
        /*
        switch (allocaInst.allocType()) {
            case IRType.Int type -> allocateValue(type);
            default -> throw new TodoException("" + allocaInst.allocType());
        }

        storeLocal(allocaInst.result());
        */
    }

    private void allocateValue(final IRType type) {
        switch (type) {
            case IRType.Int intType when intType.bits() == 32 -> {
                methodVisitor.visitInsn(Opcodes.ICONST_0);
            }
            default -> throw new TodoException("" + type);
        }
    }

    private void visitJump(final int opcode,
                           final IRValue result) {
        final var falseLabel = new Label();
        final var trueLabel = new Label();
        methodVisitor.visitJumpInsn(opcode, falseLabel);
        methodVisitor.visitInsn(Opcodes.ICONST_1);
        methodVisitor.visitJumpInsn(Opcodes.GOTO, trueLabel);
        methodVisitor.visitLabel(falseLabel);
        methodVisitor.visitInsn(Opcodes.ICONST_0);
        methodVisitor.visitLabel(trueLabel);

        final var varIndex = getLocal(result).index();
        methodVisitor.visitVarInsn(Opcodes.ISTORE, varIndex);
    }

    private void storeLocal(final IRValue value) {
        final var varIndex = getLocal(value).index();
        final var storeOpcode = resolveStoreOpcode(value);
        methodVisitor.visitVarInsn(storeOpcode, varIndex);
    }

    private int resolveStoreOpcode(final IRValue value) {
        return switch (value.type()) {
            case IRType.Int ignore -> Opcodes.ISTORE;
            default -> throw new TodoException("" + value.type());
        };
    }

    private Local getLocal(final IRValue result) {
        final var temp = (IRValue.Temp) result;
        return getLocal(temp.name());
    }

    private Local createLocal(final String name, IRType type) {
        return locals.computeIfAbsent(
                name,
                i -> new Local(++lastIndex, type));
    }

    private Local getLocal(final String name) {
        return locals.computeIfAbsent(
                name,
                i -> new Local(++lastIndex, null));
    }

    private boolean hasLocal(final String name) {
        return locals.containsKey(name);
    }

    private int resolveEQOpcode(final IRValue value) {
        return switch (value.type()) {
            case IRType.Int intType -> {
                if (intType.bits() == 32) {
                    yield Opcodes.IF_ICMPNE;
                } else {
                    throw new TodoException();
                }
            }
            default -> throw new TodoException();
        };
    }

    private int resolveLTOpcode(final IRValue value) {
        return switch (value.type()) {
            case IRType.Int intType -> {
                if (intType.bits() == 32) {
                    yield Opcodes.IF_ICMPGE;
                } else {
                    throw new TodoException();
                }
            }
            default -> throw new TodoException();
        };
    }

    private int resolveGTEOpode(final IRValue value) {
        return switch (value.type()) {
            case IRType.Int intType -> {
                if (intType.bits() == 32) {
                    yield Opcodes.IF_ICMPLT;
                } else {
                    throw new TodoException();
                }
            }
            default -> throw new TodoException();
        };
    }

    private int resolveAddOpcode(final IRValue left) {
        return switch (left.type()) {
            case IRType.Int intTye when intTye.bits() == 32 -> Opcodes.IADD;
            case IRType.Int intTye when intTye.bits() == 64 -> Opcodes.LADD;
            default -> throw new TodoException();
        };
    }

    private int resolveSubOpcode(final IRValue left) {
        return switch (left.type()) {
            case IRType.Int intTye when intTye.bits() == 32 -> Opcodes.ISUB;
            case IRType.Int intTye when intTye.bits() == 64 -> Opcodes.LSUB;
            default -> throw new TodoException();
        };
    }

    private int resolveMUlOpcode(final IRValue left) {
        return switch (left.type()) {
            case IRType.Int intTye when intTye.bits() == 32 -> Opcodes.IMUL;
            case IRType.Int intTye when intTye.bits() == 64 -> Opcodes.LMUL;
            default -> throw new TodoException();
        };
    }

    private int resolveDivOpcode(final IRValue left) {
        return switch (left.type()) {
            case IRType.Int intTye when intTye.bits() == 32 -> Opcodes.IDIV;
            case IRType.Int intTye when intTye.bits() == 64 -> Opcodes.LDIV;
            default -> throw new TodoException();
        };
    }

    private int resolveModOpcode(final IRValue left) {
        return switch (left.type()) {
            case IRType.Int intTye when intTye.bits() == 32 -> Opcodes.IREM;
            case IRType.Int intTye when intTye.bits() == 64 -> Opcodes.LREM;
            default -> throw new TodoException();
        };
    }

    private int resolveReturnOpcode(final IRValue value) {
        if (value instanceof IRValue.ConstFloat(double ignored,IRType.Float type)) {
            return resolveReturnOpcode(type);
        } else if (value instanceof IRValue.ConstBool ignored) {
            return Opcodes.IRETURN;
        } else if (value instanceof IRValue.ConstNull ignored) {
            return Opcodes.ARETURN;
        } else if (value instanceof IRValue.ConstInt(long intValue, IRType.Int type)) {
            return resolveReturnOpcode(type);
        } else if (value instanceof IRValue.Temp temp) {
            return resolveReturnOpcode(temp.type());
        }

        throw new TodoException();
    }

    private int resolveReturnOpcode(final IRType type) {
        return switch (type) {
            case IRType.Float floatType -> floatType.bits() == 32 ? Opcodes.FRETURN : Opcodes.DRETURN;
            case IRType.Int intType -> intType.bits() == 32 ? Opcodes.IRETURN : Opcodes.LRETURN;
            default -> throw new TodoException();
        };
    }

    private void emit(final IRValue value) {
        if (value instanceof IRValue.ConstFloat(double floatValue, IRType.Float type)) {
            if (type.bits() == 32) {
                methodVisitor.visitLdcInsn(Double.valueOf(floatValue).floatValue());
            } else if (type.bits() == 64) {
                methodVisitor.visitLdcInsn(floatValue);
            } else {
                throw new IllegalArgumentException("Invalid bits " + type.bits());
            }
        } else if (value instanceof IRValue.ConstBool(boolean b)) {
            methodVisitor.visitLdcInsn(b);
        } else if (value instanceof IRValue.ConstNull ignored) {
            methodVisitor.visitInsn(Opcodes.ACONST_NULL);
        } else if (value instanceof IRValue.ConstInt(long constIntValue, IRType.Int type)) {
            if (type.bits() == 32) {
                final var intValue = Long.valueOf(constIntValue).intValue();

                if (intValue >= Short.MIN_VALUE && intValue <= Short.MAX_VALUE) {
                    methodVisitor.visitIntInsn(Opcodes.SIPUSH, intValue);
                } else {
                    methodVisitor.visitIntInsn(Opcodes.BIPUSH, intValue);
                }
            } else {
                methodVisitor.visitLdcInsn(constIntValue);
            }
        } else if (value instanceof IRValue.Temp(String name, IRType type)) {
            final int index;

            if (name.startsWith("%arg")) {
                index = Integer.parseInt(name.substring(4));
            } else if (name.startsWith("%.ptr")) {
                index = Integer.parseInt(name.substring(5));
            } else if (locals.containsKey(name)) {
                index = getLocal(name).index();
            } else {
                return;
            }

            final var opcode = resolveLoadOpcode(type);
            methodVisitor.visitVarInsn(opcode, index);
        } else if (value instanceof IRValue.Named named) {
            var name = named.name();

            if (name.startsWith("@")) {
                name = name.substring(1);
            }

            final var global = codeEmitter.getGlobal(name);
            emit(global.initializer());
        } else if (value instanceof IRValue.ConstString(String stringValue)) {
            methodVisitor.visitLdcInsn(stringValue);
        } else {
            throw new TodoException();
        }
    }

    private int resolveLoadOpcode(final IRType type) {
        return switch (type) {
            case IRType.Int intType -> intType.bits() == 32
                    ? Opcodes.ILOAD
                    : Opcodes.LLOAD;
            default -> throw new TodoException();
        };
    }
}

record Local(int index, IRType type) {

}

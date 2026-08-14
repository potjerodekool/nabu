package io.github.potjerodekool.nabu.compiler.backend.asm;

import io.github.potjerodekool.nabu.compiler.debug.SourceLocation;
import io.github.potjerodekool.nabu.compiler.ir.CallKind;
import io.github.potjerodekool.nabu.compiler.ir.IRBasicBlock;
import io.github.potjerodekool.nabu.compiler.ir.IRFunction;
import io.github.potjerodekool.nabu.compiler.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.compiler.ir.types.IRType;
import io.github.potjerodekool.nabu.compiler.ir.values.IRValue;
import io.github.potjerodekool.nabu.util.Pair;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class InstructionEmitter {

    private final AsmWithStackMethodVisitor mv;
    private final ASMByteCodeEmitter codeEmitter;
    private IRInstruction.BinaryOp lastBinOp = null;
    private final LocalVarManager localVarManager;
    private final Map<String, Label> labels = new HashMap<>();
    private int lastLine = -1;

    public InstructionEmitter(final AsmWithStackMethodVisitor methodVisitor,
                              final ASMByteCodeEmitter codeEmitter,
                              final int lastIndex) {
        this.mv = methodVisitor;
        this.codeEmitter = codeEmitter;
        localVarManager = new LocalVarManager(lastIndex);
    }

    public void emit(final IRBasicBlock block) {
        var labelName = block.label();
        if (labelName.startsWith("%")) {
            labelName = labelName.substring(1);
        }

        final var label = findOrCreateLabel(labelName);
        this.mv.visitLabel(label);
    }

    public void visitLabel(final String labelName) {
        final var label = findOrCreateLabel(labelName);
        this.mv.visitLabel(label);
    }

    private boolean doVisitLine(final IRInstruction instruction) {
        if (instruction instanceof IRInstruction.Alloca) {
            return false;
        } else {
            return instruction.location() != SourceLocation.UNKNOWN
                    && instruction.location().line() != lastLine;
        }
    }

    public void emit(final IRInstruction instr) {
        if (doVisitLine(instr)) {
            mv.visitLineNumber(
                    instr.location().line(),
                    mv.getLastLabel()
            );
            lastLine = instr.location().line();
        }

        switch (instr) {
            case IRInstruction.Return returnInst -> emitReturn(returnInst);
            case IRInstruction.BinaryOp binaryOp -> emitBinOp(binaryOp);
            case IRInstruction.Alloca allocaInst -> emitAllocate(allocaInst);
            case IRInstruction.Store store -> visitStore(store);
            case IRInstruction.Load loadInst -> visitLoad(loadInst);
            case IRInstruction.Call call -> emitFunctionCall(call);
            case IRInstruction.AllocaArray allocArrayInst -> emitNewArray(allocArrayInst);
            case IRInstruction.Branch branchInst -> emitBranch(branchInst);
            case IRInstruction.Cast cast -> emitCast(cast);
            case IRInstruction.CondBranch condBranch -> emitCondBranch(condBranch);
            case IRInstruction.IndirectCall indirectCall -> emitIndirectCall(indirectCall);
            case IRInstruction.InstanceOf instanceOf -> emitInstanceOf(instanceOf);
            case IRInstruction.Throw throwInst -> emitThrow(throwInst);
            case IRInstruction.Pop ignored -> emitPop();
            case IRInstruction.HeapAlloc heapAlloc -> emitHeapAlloc(heapAlloc);
            case IRInstruction.ArrayLoad arrayLoad -> emitArrayLoad(arrayLoad);
            case IRInstruction.Phi phi -> {
                throw new IllegalStateException(
                    "Phi-instructie moet geëlimineerd zijn vóór bytecode-emissie. " +
                    "Voer PhiElimination.run() uit op de functie.");
            }
            case IRInstruction.Move move -> emitMove(move);
            case IRInstruction.ArrayLength arrayLength -> emitArrayLength(arrayLength);
            case IRInstruction.MonitorEnter monitorEnter -> emitMonitorEnter(monitorEnter);
            case IRInstruction.MonitorExit monitorExit -> emitMonitorExit(monitorExit);
            case IRInstruction.TryCatchRegion tryCatch -> {
                // TryCatchRegion is metadata — nothing to emit at bytecode level
                // The ASMByteCodeEmitter handles this via visitTryCatchBlock
            }
        }
    }

    private void emitThrow(final IRInstruction.Throw throwInst) {
        final var type = AsmHelper.toInternalName(throwInst.type());
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>", "()V", false);
        mv.visitInsn(Opcodes.ATHROW);
    }


    private void emitInstanceOf(final IRInstruction.InstanceOf instanceOf) {
        emit(instanceOf.source());
        mv.visitTypeInsn(Opcodes.INSTANCEOF, AsmHelper.toInternalName(instanceOf.type()));
    }

    private void emitPop() {
        mv.visitInsn(Opcodes.POP);
    }

    private void emitMove(final IRInstruction.Move move) {
        // Move: laad de bronwaarde, sla op in het doel-local-variable
        emit(move.value());
    }

    private void emitArrayLength(final IRInstruction.ArrayLength arrayLength) {
        emit(arrayLength.array());
        mv.visitInsn(Opcodes.ARRAYLENGTH);
    }

    private void emitMonitorEnter(final IRInstruction.MonitorEnter monitorEnter) {
        emit(monitorEnter.object());
        mv.visitInsn(Opcodes.MONITORENTER);
    }

    private void emitMonitorExit(final IRInstruction.MonitorExit monitorExit) {
        emit(monitorExit.object());
        mv.visitInsn(Opcodes.MONITOREXIT);
    }

    private void emitReturn(final IRInstruction.Return returnInst) {
        if (returnInst.value() != null) {
            emit(returnInst.value());
            final var returnOpcode = resolveReturnOpcode(returnInst.value());

            mv.visitInsn(returnOpcode);
        } else {
            mv.visitInsn(Opcodes.RETURN);
        }
    }

    private void emitBinOp(final IRInstruction.BinaryOp binaryOp) {
        if (isStringConcat(binaryOp)) {
            generateStringConcat(binaryOp);
        } else {
            emit(binaryOp.left());

            if (!isNullConst(binaryOp.right())) {
                emit(binaryOp.right());
            }
            switch (binaryOp.op()) {
                case ADD -> {
                    final var opcode = resolveAddOpcode(binaryOp.left());
                    mv.visitInsn(opcode);
                }
                case SUB -> {
                    final var opcode = resolveSubOpcode(binaryOp.left());
                    mv.visitInsn(opcode);
                }
                case MUL -> {
                    final var opcode = resolveMUlOpcode(binaryOp.left());
                    mv.visitInsn(opcode);
                }
                case DIV -> {
                    final var opcode = resolveDivOpcode(binaryOp.left());
                    mv.visitInsn(opcode);
                }
                case MOD -> {
                    final var opcode = resolveModOpcode(binaryOp.left());
                    mv.visitInsn(opcode);
                }
                case AND -> {
                    final var opcode = resolveAndOpcode(binaryOp.left());
                    mv.visitInsn(opcode);
                }
                case OR -> {
                    final var opcode = resolveOrOpcode(binaryOp.left());
                    mv.visitInsn(opcode);
                }
                case BITAND -> {
                    final var opcode = resolveBitAddOpcode(binaryOp.left());
                    mv.visitInsn(opcode);
                }
                case BITOR -> {
                    final var opcode = resolveBitOrOpcode(binaryOp.left());
                    mv.visitInsn(opcode);
                }
                case BITXOR -> {
                    final var opcode = resolveBitXOrOpcode(binaryOp.left());
                    mv.visitInsn(opcode);
                }
                case XOR -> {
                    final var opcode = resolveXorOpcode(binaryOp.left());
                    mv.visitInsn(opcode);
                }
                default -> lastBinOp = binaryOp;
            }
        }

        final var result = (IRValue.Temp) binaryOp.result();
        localVarManager.setStackValue(result);
    }

    private void generateStringConcat(final IRInstruction.BinaryOp binaryOp) {
        final var template = new StringBuilder();
        generateTemplateForStringConcat(binaryOp, template);
        final var descriptor = new StringBuilder();
        descriptor.append("(");
        generateDescriptorForStringConcat(binaryOp, descriptor);
        descriptor.append(")Ljava/lang/String;");

        final var handle = new Handle(Opcodes.H_INVOKESTATIC,
                "java/lang/invoke/StringConcatFactory",
                "makeConcatWithConstants",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;",
                false);

        if (binaryOp.left() instanceof IRValue.Temp) {
            emit(binaryOp.left());
        }
        if (binaryOp.right() instanceof IRValue.Temp) {
            emit(binaryOp.right());
        }

        mv.visitInvokeDynamicInsn(
                "makeConcatWithConstants",
                descriptor.toString(),
                handle,
                template.toString()
        );
    }

    private void generateTemplateForStringConcat(final IRInstruction.BinaryOp binOp,
                                                 final StringBuilder template) {
        final var left = binOp.left();
        final var right = binOp.right();

        if (isStringConstant(left) && left instanceof IRValue.Named named) {
            final var name = named.name().substring(1);
            final var global = codeEmitter.getGlobal(name);
            final var constString = (IRValue.ConstString) global.initializer();
            template.append(constString.value());
        } else {
            template.append("\u0001");
        }

        if (isStringConstant(right) && right instanceof IRValue.Named named) {
            final var name = named.name().substring(1);
            final var global = codeEmitter.getGlobal(name);
            final var constString = (IRValue.ConstString) global.initializer();
            template.append(constString.value());
        } else {
            template.append("\u0001");
        }
    }

    private void generateDescriptorForStringConcat(final IRInstruction.BinaryOp binOp,
                                                   final StringBuilder stringBuilder) {
        if (binOp.left() instanceof IRValue.Temp) {
            stringBuilder.append(AsmHelper.createDescriptor(binOp.left().type()));
        }

        if (binOp.right() instanceof IRValue.Temp) {
            stringBuilder.append(AsmHelper.createDescriptor(binOp.right().type()));
        }
    }

    private boolean isStringConcat(final IRInstruction.BinaryOp binaryOp) {
        if (binaryOp.op() == IRInstruction.BinaryOp.Op.ADD) {
            if (isStringConstant(binaryOp.left())) {
                return true;
            } else {
                return isStringConstant(binaryOp.right());
            }
        }

        return false;
    }

    private boolean isStringConstant(final IRValue value) {
        if (value.type() instanceof IRType.Ptr ptr) {
            if (ptr.pointee() == IRType.I8) {
                return true;
            }
        }

        return false;
        //return value instanceof Const c
        //&& STRING_TYPE.equals(c.getType());
    }

    private boolean isNullConst(final IRValue value) {
        return value instanceof IRValue.ConstNull;
    }

    private int resolveJumpOpcode() {
        if (lastBinOp == null) {
            return Opcodes.IFNE;
        }

        final var topType = mv.peek();

        if (topType != null && topType.getSort() == Type.OBJECT) {
            switch (lastBinOp.op()) {
                case EQ -> {
                    if (isNullConst(lastBinOp.right())) {
                        return Opcodes.IFNULL;
                    } else {
                        return Opcodes.IF_ACMPEQ;
                    }
                }
                case NEQ -> {
                    if (isNullConst(lastBinOp.right())) {
                        return Opcodes.IFNONNULL;
                    } else {
                        return Opcodes.IF_ACMPNE;
                    }
                }
                default -> throw new UnsupportedOperationException("Unsupported comparison op: " + lastBinOp.op());
            }
        }
        final var leftType = lastBinOp.left().type();

        return switch (lastBinOp.op()) {
            case LT -> leftType instanceof IRType.Int intBits && intBits.bits() == 64
                    ? Opcodes.IFLT
                    : Opcodes.IF_ICMPLT;
            case LTE -> leftType instanceof IRType.Int intBits && intBits.bits() == 64
                    ? Opcodes.IFLE
                    : Opcodes.IF_ICMPLE;
            case EQ -> leftType instanceof IRType.Int intBits && intBits.bits() == 64
                    ? Opcodes.IFEQ
                    : Opcodes.IF_ICMPEQ;
            case GTE -> leftType instanceof IRType.Int intBits && intBits.bits() == 64
                    ? Opcodes.IFGE
                    : Opcodes.IF_ICMPGE;
            case GT -> leftType instanceof IRType.Int intBits && intBits.bits() == 64
                    ? Opcodes.IFGT
                    : Opcodes.IF_ICMPGT;
            case NEQ -> leftType instanceof IRType.Int intBits && intBits.bits() == 64
                    ? Opcodes.IFNE
                    : Opcodes.IF_ICMPNE;
            default -> throw new UnsupportedOperationException("Unsupported comparison op: " + lastBinOp.op());
        };
    }

    private void emitCondBranch(final IRInstruction.CondBranch condBranch) {
        final var label = findOrCreateLabel(condBranch.trueLabel());

        if (lastBinOp != null) {
            final var leftType = lastBinOp.left().type();

            if (leftType instanceof IRType.Float floatType) {
                emit(lastBinOp.left());
                emit(lastBinOp.right());
                final var cmpOpcode = floatType.bits() == 64
                        ? Opcodes.DCMPL : Opcodes.FCMPL;
                mv.visitInsn(cmpOpcode);
                final var opcode = resolveFloatJumpOpcode(lastBinOp.op());
                mv.visitJumpInsn(opcode, label);
            } else if (leftType instanceof IRType.Int intType && intType.bits() == 64) {
                emit(lastBinOp.left());
                emit(lastBinOp.right());
                mv.visitInsn(Opcodes.LCMP);
                final var opcode = resolveJumpOpcode();
                mv.visitJumpInsn(opcode, label);
            } else {
                final var opcode = resolveJumpOpcode();
                if (lastBinOp != null) {
                    final var lt = lastBinOp.left().type();
                    if (lt instanceof IRType.Int intType && intType.bits() == 64) {
                        mv.visitInsn(Opcodes.LCMP);
                    }
                }
                mv.visitJumpInsn(opcode, label);
            }
        } else {
            final var opcode = Opcodes.IFNE;
            mv.visitJumpInsn(opcode, label);
        }
        lastBinOp = null;
    }

    private int resolveFloatJumpOpcode(final IRInstruction.BinaryOp.Op op) {
        return switch (op) {
            case EQ -> Opcodes.IFEQ;
            case NEQ -> Opcodes.IFNE;
            case LT -> Opcodes.IFLT;
            case LTE -> Opcodes.IFLE;
            case GT -> Opcodes.IFGT;
            case GTE -> Opcodes.IFGE;
            default -> throw new UnsupportedOperationException("Unsupported float comparison: " + op);
        };
    }

    private Label findOrCreateLabel(final String name) {
        return this.labels.computeIfAbsent(name, k -> new Label());
    }

    /**
     * Geeft een Label terug voor het gegeven blok-label (bestaand of nieuw).
     * Wordt gebruikt door ASMByteCodeEmitter voor try-catch registratie.
     */
    public Label getOrCreateLabel(final String blockLabel) {
        var name = blockLabel;
        if (name.startsWith("%")) {
            name = name.substring(1);
        }
        return findOrCreateLabel(name);
    }

    private void emitBranch(final IRInstruction.Branch branchInst) {
        var label = findOrCreateLabel(branchInst.targetLabel());
        mv.visitJumpInsn(Opcodes.GOTO, label);
    }

    private void emitCast(final IRInstruction.Cast cast) {
        emit(cast.source());
        final var type = AsmHelper.toInternalName(cast.targetType());
        mv.visitTypeInsn(Opcodes.CHECKCAST, type);
        final var result = (IRValue.Temp) cast.result();
        localVarManager.setStackValue(result);
    }

    private int resolveInvokeOpcode(final CallKind callKind) {
        return switch (callKind) {
            case INTERFACE -> Opcodes.INVOKEINTERFACE;
            case SPECIAL -> Opcodes.INVOKESPECIAL;
            case STATIC -> Opcodes.INVOKESTATIC;
            case VIRTUAL -> Opcodes.INVOKEVIRTUAL;
        };
    }

    private void emitFunctionCall(final IRInstruction.Call call) {
        final var opcode = resolveInvokeOpcode(call.callKind());
        final String descriptor = AsmHelper.createDescriptor(
                call.paramTypes(),
                call.returnType()
        );

        var functionName = call.function();
        final var sepIndex = functionName.lastIndexOf("_");
        var owner = functionName.substring(0, sepIndex).replace('_', '.');
        owner = AsmHelper.toInternalName(owner);
        functionName = functionName.substring(sepIndex + 1);

        if (isConstructorCall(call)) {
            functionName = "<init>";
        }

        call.args().forEach(this::emit);

        mv.visitMethodInsn(
                opcode,
                owner,
                functionName,
                descriptor,
                opcode == Opcodes.INVOKEINTERFACE
        );

        if (call.result() != null) {
            final var result = (IRValue.Temp) call.result();
            localVarManager.setStackValue(result);
        }
    }

    private boolean isConstructorCall(final IRInstruction.Call call) {
        return call.function().endsWith("_init")
                && call.returnType() == IRType.VOID
                && call.callKind() == CallKind.SPECIAL;
    }

    private void visitLoad(final IRInstruction.Load loadInst) {
        final var ptr = loadInst.ptr();

        if (ptr instanceof IRValue.Named named) {
            //Remove @ character.
            //@java.lang.System_out
            final var name = named.name().substring(1);
            final var classAndFieldName = name.split("_");
            final var className = classAndFieldName[0];
            final var fieldName = classAndFieldName[1];

            //TODO check static or not.
            final var opcode = named.isStatic() ? Opcodes.GETSTATIC : Opcodes.GETFIELD;
            final var owner = AsmHelper.toInternalName(className);

            final var descriptor = AsmHelper.createDescriptor(loadInst.type());

            mv.visitFieldInsn(
                    opcode,
                    owner,
                    fieldName,
                    descriptor
            );
        } else if (ptr instanceof IRValue.Temp temp) {
            final var opcode = resolveLoadOpcode(ptr);
            var name = temp.name();
            final int index;

            if (localVarManager.hasSlot(name)) {
                index = localVarManager.getSlot(name);
            } else {
                final var source = resolveName(temp);
                index = localVarManager.getSlot(source);
            }

            if (index < 0) {
                throw new IllegalArgumentException("Invalid slot name: " + name);
            }

            mv.visitVarInsn(opcode, index);
        } else {
            throw new UnsupportedOperationException("Unexpected load target: " + loadInst);
        }

        final var result = (IRValue.Temp) loadInst.result();
        localVarManager.setStackValue(result);
    }

    private String resolveName(final IRValue value) {
        if (value instanceof IRValue.Temp(String name, IRType type)) {
            if (name.startsWith("%")) {
                name = name.substring(1);
            }

            if (type instanceof IRType.Ptr) {
                name = name.replace(".ptr", "");
            }

            return name;
        } else {
            throw new IllegalArgumentException("Unknown IRValue " + value);
        }
    }

    private int resolveLoadOpcode(final IRValue value) {
        final var temp = (IRValue.Temp) value;
        var name = temp.name();

        if (name.startsWith("%") && name.endsWith(".ptr")) {
            name = name.substring(1, name.length() - 4);
        }

        var localVariable = localVarManager.getVar(name);

        if (localVariable == null) {
            var index = name.indexOf('.');

            if (index != -1) {
                name = name.substring(1, index);
            } else if (name.startsWith("%")) {
                name = name.substring(1);
            }

            localVariable = localVarManager.getVar(name);
        }

        final var type = value.type();

        //return resolveLoadOpcode(localVariable.type());
        return resolveLoadOpcode(type);
    }

    private int resolveLoadOpcode(final IRType type) {
        return switch (type) {
            case IRType.Int intType -> switch (intType.bits()) {
                case 8, 16, 32 -> Opcodes.ILOAD;
                case 64 -> Opcodes.LLOAD;
                default -> Opcodes.ILOAD;
            };
            case IRType.Float floatType -> floatType.bits() == 64 ? Opcodes.DLOAD
                    : Opcodes.FLOAD;
            case IRType.Ptr ptr -> {
                if (ptr.jvmDescriptor() != null) {
                    yield Opcodes.ALOAD;
                }

                yield resolveLoadOpcode(ptr.pointee());
            }
            case IRType.Bool ignored -> Opcodes.ILOAD;
            case IRType.Void ignored -> Opcodes.NOP;
            case IRType.Array ignored -> Opcodes.ALOAD;
            default -> throw new UnsupportedOperationException("Unsupported load type: " + type);
        };
    }

    private void visitStore(final IRInstruction.Store store) {
        String name;

        if (store.ptr() instanceof IRValue.Named named) {
            name = named.name();
            final var separatorIndex = name.lastIndexOf('_');

            if (separatorIndex > -1) {
                name = name.substring(1, separatorIndex);
            }

            final var global = codeEmitter.getGlobal(name);
            final var owner = AsmHelper.toInternalName(global.ownerType());
            final var descriptor = AsmHelper.createDescriptor(global.type());
            final var opcode = global.isStatic() ? Opcodes.PUTSTATIC : Opcodes.PUTFIELD;

            emit(store.value());

            mv.visitFieldInsn(
                    opcode,
                    owner,
                    name,
                    descriptor
            );

        } else {
            final var temp = (IRValue.Temp) store.ptr();
            name = temp.name();
            final var separatorIndex = name.lastIndexOf('.');
            if (separatorIndex > -1) {
                name = name.substring(1, separatorIndex);
            }


            final var localVariable = localVarManager.getVar(name);
            final var varIndex = localVariable.index();
            final var storeOpcode = resolveStoreOpcode(store.ptr());

            emit(store.value());

            mv.visitVarInsn(
                    storeOpcode,
                    varIndex
            );
        }

    }

    private void emitAllocate(final IRInstruction.Alloca allocaInst) {
        final var name = resolveName(allocaInst.result());
        localVarManager.allocateSlot(name, allocaInst.allocType());
    }

    private void emitNewArray(final IRInstruction.AllocaArray allocaArray) {
        emit(allocaArray.size());
        final var internalName = AsmHelper.createDescriptor(allocaArray.allocType());
        final var opcode = Opcodes.ANEWARRAY;
        mv.visitTypeInsn(opcode, internalName);
        localVarManager.setStackValue(allocaArray.result());
    }

    private void emitHeapAlloc(final IRInstruction.HeapAlloc heapAlloc) {
        final var internalName = AsmHelper.toInternalName(heapAlloc.allocType());
        mv.visitTypeInsn(Opcodes.NEW, internalName);
        mv.visitInsn(Opcodes.DUP);
        localVarManager.setStackValue(heapAlloc.result());
    }

    private void emitArrayLoad(final IRInstruction.ArrayLoad arrayLoad) {
        emit(arrayLoad.array());
        emit(arrayLoad.index());
        final var opcode = resolveArrayLoadOpcode(arrayLoad.elemType());
        mv.visitInsn(opcode);
        localVarManager.setStackValue(arrayLoad.result());
    }

    private int resolveArrayLoadOpcode(final IRType elemType) {
        return switch (elemType) {
            case IRType.Int t -> switch (t.bits()) {
                case 8 -> Opcodes.BALOAD;
                case 16 -> Opcodes.SALOAD;
                case 32 -> Opcodes.IALOAD;
                case 64 -> Opcodes.LALOAD;
                default -> Opcodes.IALOAD;
            };
            case IRType.Float t -> t.bits() == 32 ? Opcodes.FALOAD : Opcodes.DALOAD;
            case IRType.Bool ignored -> Opcodes.BALOAD;
            case IRType.Ptr ignored -> Opcodes.AALOAD;
            default -> Opcodes.AALOAD;
        };
    }

    private int resolveStoreOpcode(final IRValue value) {
        return resolveStoreOpcode(value.type());
    }

    private int resolveStoreOpcode(final IRType type) {
        return switch (type) {
            case IRType.Int(int bits) -> switch (bits) {
                case 8, 16, 32 -> Opcodes.ISTORE;
                case 64 -> Opcodes.LSTORE;
                default -> Opcodes.ISTORE;
            };
            case IRType.Float floatType -> floatType.bits() == 64 ? Opcodes.DSTORE : Opcodes.FSTORE;
            case IRType.Ptr ptr -> {
                if (ptr.jvmDescriptor() != null) {
                    yield resolveStoreOpcodeFromDescriptor(ptr.jvmDescriptor());
                }
                yield resolveStoreOpcode(ptr.pointee());
            }
            case IRType.Bool ignored -> Opcodes.ISTORE;
            case IRType.Array ignored -> Opcodes.ASTORE;
            case IRType.Void ignored -> Opcodes.NOP;
            default -> throw new UnsupportedOperationException("Unsupported store type: " + type);
        };
    }

    private int resolveStoreOpcodeFromDescriptor(final String descriptor) {
        return switch (descriptor.charAt(0)) {
            case 'L', '[' -> Opcodes.ASTORE;
            case 'B', 'S', 'C', 'I', 'Z' -> Opcodes.ISTORE;
            case 'J' -> Opcodes.LSTORE;
            case 'F' -> Opcodes.FSTORE;
            case 'D' -> Opcodes.DSTORE;
            default -> throw new UnsupportedOperationException("Unknown descriptor: " + descriptor);
        };
    }

    public LocalVariable createLocal(final String name, IRType type) {
        localVarManager.allocateSlot(name, type);
        return localVarManager.getVar(name);
    }

    private LocalVariable getLocal(final String name) {
        return localVarManager.getOrCreateVar(name);
    }

    private int resolveAddOpcode(final IRValue value) {
        return resolveAddOpcode(value.type());
    }

    private int resolveAddOpcode(final IRType type) {
        return switch (type) {
            case IRType.Int intType -> intType.bits() == 64 ? Opcodes.LADD : Opcodes.IADD;
            case IRType.Float floatType -> floatType.bits() == 32 ? Opcodes.FADD : Opcodes.DADD;
            case IRType.Ptr ptr -> resolveAddOpcode(ptr.pointee());
            default -> throw new UnsupportedOperationException("Unsupported add type: " + type);
        };
    }

    private int resolveBitAddOpcode(final IRValue value) {
        return resolveBitAddOpcode(value.type());
    }

    private int resolveBitAddOpcode(final IRType type) {
        return switch (type) {
            case IRType.Int intType -> intType.bits() == 64 ? Opcodes.LAND : Opcodes.IAND;
            default -> throw new UnsupportedOperationException("Unsupported bitand type: " + type);
        };
    }

    private int resolveBitOrOpcode(final IRValue value) {
        return resolveBitOrOpcode(value.type());
    }

    private int resolveBitOrOpcode(final IRType type) {
        return switch (type) {
            case IRType.Int intType -> intType.bits() == 64 ? Opcodes.LOR : Opcodes.IOR;
            default -> throw new UnsupportedOperationException("Unsupported bitor type: " + type);
        };
    }

    private int resolveBitXOrOpcode(final IRValue value) {
        return resolveBitXOrOpcode(value.type());
    }

    private int resolveBitXOrOpcode(final IRType type) {
        return switch (type) {
            case IRType.Int intType -> intType.bits() == 64 ? Opcodes.LXOR : Opcodes.IXOR;
            default -> throw new UnsupportedOperationException("Unsupported bitxor type: " + type);
        };
    }

    private int resolveSubOpcode(final IRValue left) {
        return resolveSubOpcode(left.type());
    }

    private int resolveSubOpcode(final IRType type) {
        return switch (type) {
            case IRType.Int intType -> intType.bits() == 64 ? Opcodes.LSUB : Opcodes.ISUB;
            case IRType.Float floatType -> floatType.bits() == 32 ? Opcodes.FSUB : Opcodes.DSUB;
            case IRType.Ptr ptr -> resolveSubOpcode(ptr.pointee());
            default -> throw new UnsupportedOperationException("Unsupported sub type: " + type);
        };
    }

    private int resolveMUlOpcode(final IRValue left) {
        return switch (left.type()) {
            case IRType.Int intType -> intType.bits() == 64 ? Opcodes.LMUL : Opcodes.IMUL;
            case IRType.Float floatType -> floatType.bits() == 32 ? Opcodes.FMUL : Opcodes.DMUL;
            default -> throw new UnsupportedOperationException("Unsupported mul type: " + left.type());
        };
    }

    private int resolveDivOpcode(final IRValue left) {
        return switch (left.type()) {
            case IRType.Int intType -> intType.bits() == 64 ? Opcodes.LDIV : Opcodes.IDIV;
            case IRType.Float floatType -> floatType.bits() == 32 ? Opcodes.FDIV : Opcodes.DDIV;
            default -> throw new UnsupportedOperationException("Unsupported div type: " + left.type());
        };
    }

    private int resolveModOpcode(final IRValue left) {
        return switch (left.type()) {
            case IRType.Int intType -> intType.bits() == 64 ? Opcodes.LREM : Opcodes.IREM;
            case IRType.Float floatType -> floatType.bits() == 32 ? Opcodes.FREM : Opcodes.DREM;
            default -> throw new UnsupportedOperationException("Unsupported mod type: " + left.type());
        };
    }

    private int resolveAndOpcode(final IRValue left) {
        return switch (left.type()) {
            case IRType.Int intType -> intType.bits() == 64 ? Opcodes.LAND : Opcodes.IAND;
            default -> throw new UnsupportedOperationException("Unsupported and type: " + left.type());
        };
    }

    private int resolveOrOpcode(final IRValue left) {
        return switch (left.type()) {
            case IRType.Int intType -> intType.bits() == 64 ? Opcodes.LOR : Opcodes.IOR;
            default -> throw new UnsupportedOperationException("Unsupported or type: " + left.type());
        };
    }

    private int resolveXorOpcode(final IRValue left) {
        return switch (left.type()) {
            case IRType.Int intType -> intType.bits() == 64 ? Opcodes.LXOR : Opcodes.IXOR;
            default -> throw new UnsupportedOperationException("Unsupported xor type: " + left.type());
        };
    }

    private int resolveReturnOpcode(final IRValue value) {
        if (value instanceof IRValue.ConstFloat(double ignored, IRType.Float type)) {
            return resolveReturnOpcode(type);
        } else if (value instanceof IRValue.ConstBool ignored) {
            return Opcodes.IRETURN;
        } else if (value instanceof IRValue.ConstNull ignored) {
            return Opcodes.ARETURN;
        } else if (value instanceof IRValue.ConstInt(long intValue, IRType.Int type)) {
            return resolveReturnOpcode(type);
        } else if (value instanceof IRValue.Temp temp) {
            return resolveReturnOpcode(temp.type());
        } else if (value instanceof IRValue.Values(List<IRValue> values)) {
            return resolveReturnOpcode(values.getLast());
        } else if (value instanceof IRValue.Named named) {
            return resolveReturnOpcode(named.type());
        }

        throw new UnsupportedOperationException("Cannot resolve return opcode for: " + value.getClass().getSimpleName());
    }

    private int resolveReturnOpcode(final IRType type) {
        return switch (type) {
            case IRType.Float floatType -> floatType.bits() == 32 ? Opcodes.FRETURN : Opcodes.DRETURN;
            case IRType.Int intType -> intType.bits() == 64 ? Opcodes.LRETURN
                    : Opcodes.IRETURN;
            case IRType.Ptr ptr -> {
                final var desc = ptr.jvmDescriptor();
                if (desc != null) {
                    yield resolveReturnOpcodeFromDescriptor(desc);
                }
                yield resolveReturnOpcode(ptr.pointee());
            }
            case IRType.Bool ignored -> Opcodes.IRETURN;
            case IRType.Void ignored -> Opcodes.RETURN;
            case IRType.Array ignored -> Opcodes.ARETURN;
            default -> throw new UnsupportedOperationException("Unsupported return type: " + type);
        };
    }

    private int resolveReturnOpcodeFromDescriptor(final String descriptor) {
        return switch (descriptor.charAt(0)) {
            case 'L', '[' -> Opcodes.ARETURN;
            case 'B', 'S', 'C', 'I', 'Z' -> Opcodes.IRETURN;
            case 'J' -> Opcodes.LRETURN;
            case 'F' -> Opcodes.FRETURN;
            case 'D' -> Opcodes.DRETURN;
            default -> throw new UnsupportedOperationException("Unknown return descriptor: " + descriptor);
        };
    }

    private void emitIndirectCall(final IRInstruction.IndirectCall call) {
        emit(call.callee());
        for (final var arg : call.args()) {
            emit(arg);
        }
        final var descriptor = AsmHelper.createDescriptor(call.fnType().paramTypes(), call.fnType().returnType());
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/invoke/MethodHandle",
                "invoke",
                descriptor,
                false
        );
        final var result = call.result();
        if (result != null) {
            final var opcode = resolveStoreOpcode(result.type());
            final var temp = (IRValue.Temp) result;
            final int index;
            if (temp.name().startsWith("%arg")) {
                index = Integer.parseInt(temp.name().substring(4));
            } else if (localVarManager.hasSlot(temp.name())) {
                index = localVarManager.getSlot(temp.name());
            } else {
                index = Integer.parseInt(temp.name().replace("%", ""));
            }
            mv.visitVarInsn(opcode, index);
        }
    }

    private void emit(final IRValue value) {
        switch (value) {
            case IRValue.ConstFloat(double floatValue, IRType.Float type) -> {
                if (type.bits() == 32) {
                    mv.visitLdcInsn(Double.valueOf(floatValue).floatValue());
                } else if (type.bits() == 64) {
                    mv.visitLdcInsn(floatValue);
                } else {
                    throw new IllegalArgumentException("Invalid bits " + type.bits());
                }
            }
            case IRValue.ConstBool(boolean b) -> {
                if (b) {
                    mv.visitInsn(Opcodes.ICONST_1);
                } else {
                    mv.visitInsn(Opcodes.ICONST_0);
                }
            }
            case IRValue.ConstNull ignored -> mv.visitInsn(Opcodes.ACONST_NULL);
            case IRValue.ConstInt(long constIntValue, IRType.Int type) -> {
                if (type.bits() == 64) {

                    if (constIntValue == 0) {
                        mv.visitInsn(Opcodes.LCONST_0);
                    } else if (constIntValue == 1) {
                        mv.visitInsn(Opcodes.LCONST_1);
                    } else {
                        mv.visitLdcInsn(constIntValue);
                    }
                } else if (type.bits() == 32) {
                    final var intValue = Long.valueOf(constIntValue).intValue();

                    switch (intValue) {
                        case -1 -> mv.visitInsn(Opcodes.ICONST_M1);
                        case 0 -> mv.visitInsn(Opcodes.ICONST_0);
                        case 1 -> mv.visitInsn(Opcodes.ICONST_1);
                        case 2 -> mv.visitInsn(Opcodes.ICONST_2);
                        case 3 -> mv.visitInsn(Opcodes.ICONST_3);
                        case 4 -> mv.visitInsn(Opcodes.ICONST_4);
                        case 5 -> mv.visitInsn(Opcodes.ICONST_5);
                        default -> {
                            if (intValue >= Short.MIN_VALUE && intValue <= Short.MAX_VALUE) {
                                mv.visitIntInsn(Opcodes.SIPUSH, intValue);
                            } else {
                                mv.visitIntInsn(Opcodes.BIPUSH, intValue);
                            }
                        }
                    }
                } else {
                    mv.visitLdcInsn(constIntValue);
                }
            }
            case IRValue.Temp(String name, IRType type) -> {
                if (localVarManager.isOnStack(name)) {
                    return;
                }

                final int index;

                if (name.startsWith("%arg")) {
                    index = Integer.parseInt(name.substring(4));
                } else if (name.startsWith("%.ptr")) {
                    index = Integer.parseInt(name.substring(5));
                } else if (localVarManager.hasSlot(name)) {
                    index = getLocal(name).index();
                } else {
                    if (name.startsWith("%")) {
                        name = name.substring(1);
                    }

                    if (localVarManager.hasSlot(name)) {
                        index = getLocal(name).index();
                    } else {
                        index = Integer.parseInt(name);
                    }
                }

                final var opcode = resolveLoadOpcode(type);

                if (index == -1) {
                    throw new IllegalStateException("Invalid local variable index for " + name);
                }

                mv.visitVarInsn(opcode, index);
            }
            case IRValue.Named named -> {
                var name = named.name();

                var global = codeEmitter.getGlobal(name);

                if (global == null && !name.startsWith("@")) {
                    global = codeEmitter.getGlobal("@" + name);
                }

                if (global == null && name.startsWith("@")) {
                    name = name.substring(1);
                    global = codeEmitter.getGlobal(name);
                }

                if (named.name().startsWith("@.str.") && global.initializer() != null) {
                    emit(global.initializer());
                    return;
                }

                final var owner = AsmHelper.toInternalName(named.ownerType());
                final var descriptor = AsmHelper.createDescriptor(named.type());

                mv.visitFieldInsn(
                        Opcodes.GETFIELD,
                        owner,
                        name,
                        descriptor
                );

                //emit(global.initializer());
            }
            case IRValue.ConstString(String stringValue) -> mv.visitLdcInsn(stringValue);
            case IRValue.Values(List<IRValue> values) -> values.forEach(this::emit);
            case IRValue.ConstUndef constUndef -> {
                //IGNORE
            }
            case IRValue.ConstClass(IRType.Ptr type) -> {
                final var descriptor = type.jvmDescriptor();
                final var asmType = Type.getType(descriptor);
                mv.visitLdcInsn(asmType);
            }
            case IRValue.FunctionRef(String name, IRType.Function fnType) -> {
                final var owner = name.contains(".")
                        ? name.substring(0, name.lastIndexOf('.')).replace('.', '/')
                        : "java/lang/invoke/MethodHandles";
                final var methodName = name.contains(".")
                        ? name.substring(name.lastIndexOf('.') + 1)
                        : name;
                final var descriptor = AsmHelper.createDescriptor(fnType);
                final var handle = new Handle(
                        Opcodes.H_INVOKESTATIC,
                        owner,
                        methodName,
                        descriptor,
                        false
                );
                mv.visitLdcInsn(handle);
            }
            case null, default -> throw new IllegalStateException("Unexpected IRValue: " + (value != null ? value.getClass().getSimpleName() : "null"));
        }
    }

    public void visitLocalVariables(final IRFunction function) {
        final var parameterLabels = resolveLabelsForParameter(function);
        final var parameterStart = parameterLabels.first();
        final var parameterEnd = parameterLabels.second();

        for (final var param : function.params) {
            final var temp = (IRValue.Temp) param;
            final var paramName = temp.name().substring(1);
            final int index;

            if (localVarManager.hasSlot(temp.name())) {
                index = localVarManager.getSlot(temp.name());
            } else {
                index = localVarManager.getSlot(paramName);
            }

            final var paramDescriptor = AsmHelper.createDescriptor(temp.type());

            mv.visitLocalVariable(
                    paramName,
                    paramDescriptor,
                    null,
                    parameterStart,
                    parameterEnd,
                    index
            );
        }
    }


    private Pair<Label, Label> resolveLabelsForParameter(final IRFunction function) {
        final var start = labels.get(function.blocks().getFirst().label());
        final var end = labels.get("END");
        return new Pair<>(start, end);
    }

}
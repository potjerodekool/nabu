package io.github.potjerodekool.nabu.compiler.backend.asm;

import io.github.potjerodekool.nabu.backend.jvm.BytecodeHelper;
import io.github.potjerodekool.nabu.backend.jvm.SlotAllocator;
import io.github.potjerodekool.nabu.debug.SourceLocation;
import io.github.potjerodekool.nabu.backend.ir.CallKind;
import io.github.potjerodekool.nabu.backend.ir.IRBasicBlock;
import io.github.potjerodekool.nabu.backend.ir.IRFunction;
import io.github.potjerodekool.nabu.backend.ir.IRGlobal;
import io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Emitteert de instructies van één functie naar ASM-bytecode.
 *
 * Alle SSA-waarden worden via JVM-local-variable-slots geadresseerd
 * (zie {@link SlotAllocator}): elke instructie die een waarde produceert
 * slaat die op in het slot van het resultaat, en elke referentie naar
 * een waarde laadt uit het bijbehorende slot.
 */
public class FunctionEmitter {

    private final MethodVisitor mv;
    private final AsmByteCodeEmitter codeEmitter;
    private final SlotAllocator slots;
    private final String ownerInternalName;
    private final List<IRBasicBlock> blocks;
    private final Map<String, Label> labels = new HashMap<>();

    private final Map<String, IRInstruction.BinaryOp> pendingComparisons = new HashMap<>();
    private final Deque<IRValue> pendingConstructorAllocs = new ArrayDeque<>();
    private int lastLine = -1;
    private Label currentLabel = null;

    public FunctionEmitter(final MethodVisitor methodVisitor,
                           final AsmByteCodeEmitter codeEmitter,
                           final SlotAllocator slots,
                           final String ownerInternalName,
                           final List<IRBasicBlock> blocks) {
        this.mv = methodVisitor;
        this.codeEmitter = codeEmitter;
        this.slots = slots;
        this.ownerInternalName = ownerInternalName;
        this.blocks = blocks;
    }

    /**
     * Geeft de slot-toewijzer van deze functie.
     */
    public SlotAllocator slots() {
        return slots;
    }

    // -------------------------------------------------------
    // Blokken en labels
    // -------------------------------------------------------

    public void emitBlock(final IRBasicBlock block) {
        visitLabel(block.label());
    }

    public void visitLabel(final String labelName) {
        final var label = getOrCreateLabel(labelName);
        mv.visitLabel(label);
        currentLabel = label;
    }

    public Label getOrCreateLabel(final String blockLabel) {
        return labels.computeIfAbsent(SlotAllocator.normalize(blockLabel), k -> new Label());
    }

    // -------------------------------------------------------
    // Instructies
    // -------------------------------------------------------

    public void emit(final IRInstruction instr) {
        maybeVisitLineNumber(instr);

        switch (instr) {
            case IRInstruction.Return returnInst -> emitReturn(returnInst);
            case IRInstruction.BinaryOp binaryOp -> emitBinOp(binaryOp);
            case IRInstruction.Alloca ignored -> {
                // Alloca is alleen een naam; het slot wordt luierg toegekend
                // bij de eerste referentie naar de pointer.
            }
            case IRInstruction.Store store -> emitStore(store);
            case IRInstruction.Load load -> emitLoad(load);
            case IRInstruction.Call call -> emitCall(call);
            case IRInstruction.AllocaArray allocArray -> emitNewArray(allocArray);
            case IRInstruction.Branch branch -> mv.visitJumpInsn(Opcodes.GOTO, getOrCreateLabel(branch.targetLabel()));
            case IRInstruction.Cast cast -> emitCast(cast);
            case IRInstruction.CondBranch condBranch -> emitCondBranch(condBranch);
            case IRInstruction.IndirectCall indirectCall -> emitIndirectCall(indirectCall);
            case IRInstruction.InstanceOf instanceOf -> emitInstanceOf(instanceOf);
            case IRInstruction.Throw throwInst -> emitThrow(throwInst);
            case IRInstruction.Pop ignored -> {
                // No-op: elke geproduceerde waarde wordt direct in een slot
                // opgeslagen (storeResult), dus er staat niets op de stack
                // dat weggegooid hoeft te worden.
            }
            case IRInstruction.HeapAlloc heapAlloc -> emitHeapAlloc(heapAlloc);
            case IRInstruction.ArrayLoad arrayLoad -> emitArrayLoad(arrayLoad);
            case IRInstruction.ArrayStore arrayStore -> emitArrayStore(arrayStore);
            case IRInstruction.Phi phi -> throw new IllegalStateException(
                    "Phi-instructie moet geëlimineerd zijn vóór bytecode-emissie. " +
                    "Voer PhiElimination.run() uit op de functie.");
            case IRInstruction.Move move -> emitMove(move);
            case IRInstruction.ArrayLength arrayLength -> {
                emitValue(arrayLength.array());
                mv.visitInsn(Opcodes.ARRAYLENGTH);
                storeResult(arrayLength.result());
            }
            case IRInstruction.MonitorEnter monitorEnter -> {
                emitValue(monitorEnter.object());
                mv.visitInsn(Opcodes.MONITORENTER);
            }
            case IRInstruction.MonitorExit monitorExit -> {
                emitValue(monitorExit.object());
                mv.visitInsn(Opcodes.MONITOREXIT);
            }
            case IRInstruction.TryCatchRegion ignored -> {
                // TryCatchRegion is metadata; de try-catch-blokken worden
                // door Asm2ByteCodeEmitter geregistreerd.
            }
        }
    }

    private void maybeVisitLineNumber(final IRInstruction instr) {
        if (instr instanceof IRInstruction.Alloca) {
            return;
        }
        if (instr.location() == SourceLocation.UNKNOWN || !instr.location().isKnown()) {
            return;
        }
        if (instr.location().line() == lastLine || currentLabel == null) {
            return;
        }

        mv.visitLineNumber(instr.location().line(), currentLabel);
        lastLine = instr.location().line();
    }

    private void emitThrow(final IRInstruction.Throw throwInst) {
        if (throwInst.result() != null) {
            // Er is een concreet exception-object (bv. herworp van een
            // gevangen exception): gooi dat aanroepobject zelf.
            emitValue(throwInst.result());
        } else {
            // Synthetische throw van een type: alloceer en initïaliseer.
            final var type = BytecodeHelper.toInternalName(throwInst.type());
            mv.visitTypeInsn(Opcodes.NEW, type);
            mv.visitInsn(Opcodes.DUP);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>", "()V", false);
        }
        mv.visitInsn(Opcodes.ATHROW);
    }

    private void emitInstanceOf(final IRInstruction.InstanceOf instanceOf) {
        emitValue(instanceOf.source());
        mv.visitTypeInsn(Opcodes.INSTANCEOF, BytecodeHelper.toInternalName(instanceOf.type()));
        storeResult(instanceOf.result());
    }

    private void emitMove(final IRInstruction.Move move) {
        emitValue(move.value());
        storeResult(move.result());
    }

    private void emitReturn(final IRInstruction.Return returnInst) {
        if (returnInst.value() != null) {
            emitValue(returnInst.value());
            mv.visitInsn(resolveReturnOpcode(returnInst.value()));
        } else {
            mv.visitInsn(Opcodes.RETURN);
        }
    }

    private void emitBinOp(final IRInstruction.BinaryOp binaryOp) {
        if (isStringConcat(binaryOp)) {
            generateStringConcat(binaryOp);
            return;
        }

        if (isComparison(binaryOp.op())) {
            // Vergelijkingen produceren geen directe waarde; de operanden
            // worden door de consument geëmitteerd (de eropvolgende
            // CondBranch of een materialisatie van de boolean).
            pendingComparisons.put(IRValue.nameOf(binaryOp.result()), binaryOp);
            return;
        }

        emitValue(binaryOp.left());

        if (!(binaryOp.right() instanceof IRValue.ConstNull)) {
            emitValue(binaryOp.right());
        }

        mv.visitInsn(resolveArithmeticOpcode(binaryOp));
        storeResult(binaryOp.result());
    }

    private boolean isComparison(final IRInstruction.BinaryOp.Op op) {
        return switch (op) {
            case EQ, NEQ, LT, LTE, GT, GTE -> true;
            default -> false;
        };
    }

    private int resolveArithmeticOpcode(final IRInstruction.BinaryOp binaryOp) {
        return switch (binaryOp.op()) {
            case ADD -> resolveAddOpcode(binaryOp.left());
            case SUB -> resolveSubOpcode(binaryOp.left());
            case MUL -> resolveMulOpcode(binaryOp.left());
            case DIV -> resolveDivOpcode(binaryOp.left());
            case MOD -> resolveModOpcode(binaryOp.left());
            case AND -> resolveAndOpcode(binaryOp.left());
            case OR -> resolveOrOpcode(binaryOp.left());
            case XOR -> resolveXorOpcode(binaryOp.left());
            case BITAND -> resolveBitAndOpcode(binaryOp.left());
            case BITOR -> resolveBitOrOpcode(binaryOp.left());
            case BITXOR -> resolveBitXorOpcode(binaryOp.left());
            default -> throw new UnsupportedOperationException("Geen rekenopcode voor: " + binaryOp.op());
        };
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
            emitValue(binaryOp.left());
        }
        if (binaryOp.right() instanceof IRValue.Temp) {
            emitValue(binaryOp.right());
        }

        mv.visitInvokeDynamicInsn(
                "makeConcatWithConstants",
                descriptor.toString(),
                handle,
                template.toString()
        );
        storeResult(binaryOp.result());
    }

    private void generateTemplateForStringConcat(final IRInstruction.BinaryOp binaryOp,
                                                 final StringBuilder template) {
        final var left = binaryOp.left();
        final var right = binaryOp.right();

        appendTemplatePart(left, template);
        appendTemplatePart(right, template);
    }

    private void appendTemplatePart(final IRValue value,
                                    final StringBuilder template) {
        if (isStringConstant(value) && value instanceof IRValue.Named named) {
            final var global = findGlobal(named.name());
            if (global != null && global.initializer() instanceof IRValue.ConstString(String value1)) {
                template.append(value1);
                return;
            }
        }
        template.append("\u0001");
    }

    private void generateDescriptorForStringConcat(final IRInstruction.BinaryOp binaryOp,
                                                   final StringBuilder stringBuilder) {
        if (binaryOp.left() instanceof IRValue.Temp) {
            stringBuilder.append(BytecodeHelper.createDescriptor(binaryOp.left().type()));
        }
        if (binaryOp.right() instanceof IRValue.Temp) {
            stringBuilder.append(BytecodeHelper.createDescriptor(binaryOp.right().type()));
        }
    }

    private boolean isStringConcat(final IRInstruction.BinaryOp binaryOp) {
        if (binaryOp.op() == IRInstruction.BinaryOp.Op.ADD) {
            if (isStringConstant(binaryOp.left())) {
                return true;
            }
            return isStringConstant(binaryOp.right());
        }
        return false;
    }

    private boolean isStringConstant(final IRValue value) {
        return value.type() instanceof IRType.Ptr ptr && ptr.pointee() == IRType.I8;
    }

    // -------------------------------------------------------
    // Geheugen: loads en stores
    // -------------------------------------------------------

    private void emitLoad(final IRInstruction.Load load) {
        final var ptr = load.ptr();

        switch (ptr) {
            case IRValue.Named named -> {
                final var global = findGlobal(named.name());

                if (isStringLiteral(named, global)) {
                    emitValue(global.initializer());
                } else if (named.isStatic() || (global != null && global.isStatic())) {
                    mv.visitFieldInsn(
                            Opcodes.GETSTATIC,
                            resolveOwnerName(named, global),
                            resolveFieldName(named, global),
                            BytecodeHelper.createDescriptor(load.result().type())
                    );
                } else {
                    mv.visitFieldInsn(
                            Opcodes.GETFIELD,
                            BytecodeHelper.toInternalName(named.ownerType()),
                            named.name(),
                            BytecodeHelper.createDescriptor(load.type())
                    );
                }
            }
            case IRValue.Temp temp -> {
                final var opcode = resolveLoadOpcode(load.result().type());
                mv.visitVarInsn(opcode, slots.slotOf(temp.name(), temp.type()));
            }
            case IRValue.Values values -> values.values().forEach(this::emitValue);
            case null, default -> throw new UnsupportedOperationException("Unexpected load target: " + load);
        }

        storeResult(load.result());
    }

    private void emitStore(final IRInstruction.Store store) {
        switch (store.ptr()) {
            case IRValue.Named named -> {
                final var global = findGlobal(named.name());
                final var isStatic = named.isStatic() || (global != null && global.isStatic());

                emitValue(store.value());

                if (isStatic) {
                    mv.visitFieldInsn(
                            Opcodes.PUTSTATIC,
                            resolveOwnerName(named, global),
                            resolveFieldName(named, global),
                            BytecodeHelper.createDescriptor(
                                    global != null ? global.type() : named.type())
                    );
                } else {
                    mv.visitFieldInsn(
                            Opcodes.PUTFIELD,
                            BytecodeHelper.toInternalName(named.ownerType()),
                            named.name(),
                            BytecodeHelper.createDescriptor(named.type())
                    );
                }
            }
            case IRValue.Values values -> {
                // Emit only the receiver chain (all elements except the last Named field metadata).
                // The Named is used only for PUTFIELD's field name/descriptor.
                final var named = (IRValue.Named) values.values().getLast();
                values.values().subList(0, values.values().size() - 1).forEach(this::emitValue);
                emitValue(store.value());

                mv.visitFieldInsn(
                        Opcodes.PUTFIELD,
                        BytecodeHelper.toInternalName(named.ownerType()),
                        named.name(),
                        BytecodeHelper.createDescriptor(named.type())
                );
            }
            case IRValue.Temp temp -> {
                emitValue(store.value());
                mv.visitVarInsn(
                        resolveStoreOpcode(store.value().type()),
                        slots.slotOf(temp.name(), temp.type())
                );
            }
            case null, default -> throw new UnsupportedOperationException("Unexpected store target: " + store);
        }
    }

    /**
     * Heap-allocatie voor een nieuw object. De eigenlijke NEW+DUP wordt pas
     * bij de constructor-aanroep (zie {@link #emitConstructorCall}) geëmitteerd,
     * omdat de referentie daar op de stack nodig is. Hier wordt alleen het
     * resultaat-slot geregistreerd.
     */
    private void emitHeapAlloc(final IRInstruction.HeapAlloc heapAlloc) {
        pendingConstructorAllocs.addLast(heapAlloc.result());
    }

    private void emitNewArray(final IRInstruction.AllocaArray allocaArray) {
        emitValue(allocaArray.size());
        // ANEWARRAY verwacht het componenttype; allocType kan zelf al een
        // arraytype zijn (Ptr/Array), leidt dan het component af.
        final var componentType = switch (allocaArray.allocType()) {
            case IRType.Array a -> a.elem();
            case IRType.Ptr p -> p.pointee();
            case IRType t -> t;
        };
        mv.visitTypeInsn(Opcodes.ANEWARRAY, BytecodeHelper.toInternalName(componentType));
        storeResult(allocaArray.result());
    }

    private void emitArrayLoad(final IRInstruction.ArrayLoad arrayLoad) {
        emitValue(arrayLoad.array());
        emitValue(arrayLoad.index());
        mv.visitInsn(resolveArrayLoadOpcode(arrayLoad.elemType()));
        storeResult(arrayLoad.result());
    }

    private void emitArrayStore(final IRInstruction.ArrayStore arrayStore) {
        emitValue(arrayStore.array());
        emitValue(arrayStore.index());
        emitValue(arrayStore.value());
        mv.visitInsn(resolveArrayStoreOpcode(arrayStore.elemType()));
    }

    private int resolveArrayStoreOpcode(final IRType elemType) {
        return switch (elemType) {
            case IRType.Int t -> switch (t.bits()) {
                case 8 -> Opcodes.BASTORE;
                case 16 -> Opcodes.SASTORE;
                case 32 -> Opcodes.IASTORE;
                case 64 -> Opcodes.LASTORE;
                default -> Opcodes.IASTORE;
            };
            case IRType.Float t -> t.bits() == 32 ? Opcodes.FASTORE : Opcodes.DASTORE;
            case IRType.Bool ignored -> Opcodes.BASTORE;
            default -> Opcodes.AASTORE;
        };
    }

    private void emitCast(final IRInstruction.Cast cast) {
        emitValue(cast.source());
        final var conversionOpcode = resolveConversionOpcode(
                cast.source().type(),
                cast.targetType()
        );

        if (conversionOpcode == Opcodes.NOP) {
            // Geen conversie nodig (bv. byte -> int, of gelijke types)
        } else if (conversionOpcode == Integer.MIN_VALUE) {
            final var castInternalName = BytecodeHelper.toInternalName(cast.targetType());
            final String safeCastName = isInternalName(castInternalName)
                    ? castInternalName : "java/lang/Object";
            mv.visitTypeInsn(Opcodes.CHECKCAST, safeCastName);
        } else {
            // Primitieve numerieke conversie (bv. I2F, F2I, L2D, ...)
            mv.visitInsn(conversionOpcode);
        }
        storeResult(cast.result());
    }

    /**
     * Valideert een internal-name-string als geldige JVM-class-referentie.
     * Method-descriptors (bv. van verkeerd gemappe Cast-targets) worden
     * vermeden — anders keurt de ASM-verifier de classfile af met
     * "found ." (uninitialized descriptor-wat).
     */
    private static boolean isInternalName(final String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        // Internal names beginnen met een letter of '_', gevolgd door
        // letters, cijfers, '_' of '/'. method-descriptors bevatten ().
        return !name.contains("(")
                && !name.contains(")")
                && !name.contains(";")
                && name.matches("[\\w/$]+");
    }

    // Geef de JVM-conversie-opcode voor een primitieve cast; Opcodes.NOP voor
    // een nulloperatie en Integer.MIN_VALUE voor een referentie-CHECKCAST.
    private int resolveConversionOpcode(final IRType source,
                                        final IRType target) {
        if (source instanceof IRType.Int sourceInt) {
            if (target instanceof IRType.Int targetInt) {
                if (sourceInt.bits() == targetInt.bits()) {
                    return Opcodes.NOP;
                }
                if (targetInt.bits() == 32 && sourceInt.bits() < 32) {
                    // byte/short/char op de JVM-stack is al een int
                    return Opcodes.NOP;
                }
                if (sourceInt.bits() == 64 && targetInt.bits() == 32) {
                    return Opcodes.L2I;
                }
                if (targetInt.bits() == 64) {
                    return Opcodes.I2L;
                }
                if (targetInt.bits() == 8) {
                    return Opcodes.I2B;
                }
                if (targetInt.bits() == 16) {
                    return Opcodes.I2S;
                }
                return Opcodes.NOP;
            }
            if (target instanceof IRType.Float targetFloat) {
                if (sourceInt.bits() == 64) {
                    return targetFloat.bits() == 32 ? Opcodes.L2F : Opcodes.L2D;
                }
                return targetFloat.bits() == 32 ? Opcodes.I2F : Opcodes.I2D;
            }
        } else if (source instanceof IRType.Float sourceFloat) {
            if (target instanceof IRType.Int targetInt) {
                if (sourceFloat.bits() == 64) {
                    return targetInt.bits() == 64 ? Opcodes.D2L : Opcodes.D2I;
                }
                return targetInt.bits() == 64 ? Opcodes.F2L : Opcodes.F2I;
            }
            if (target instanceof IRType.Float targetFloat) {
                if (sourceFloat.bits() == targetFloat.bits()) {
                    return Opcodes.NOP;
                }
                return sourceFloat.bits() == 32 ? Opcodes.F2D : Opcodes.D2F;
            }
        }

        return Integer.MIN_VALUE;
    }

    // -------------------------------------------------------
    // Aanroepen
    // -------------------------------------------------------

    private void emitCall(final IRInstruction.Call call) {
        final var opcode = resolveInvokeOpcode(call.callKind());
        final var descriptor = BytecodeHelper.createDescriptor(
                call.paramTypes(),
                call.returnType()
        );

        var functionName = call.function();
        final var sepIndex = functionName.lastIndexOf('_');
        final String owner;
        if (sepIndex > -1) {
            final var methodName = functionName.substring(sepIndex + 1);
            final var mangledOwner = functionName.substring(0, sepIndex).replace('_', '.');
            final var receiverType = call.args().isEmpty() ? null : call.args().getFirst().type();

            if ("clone".equals(methodName)
                    && receiverType instanceof IRType.Ptr ptr
                    && ptr.jvmDescriptor() != null
                    && ptr.jvmDescriptor().startsWith("[")) {
                // Array.clone(): de MethodRef-owner is het arraytype zelf
                // ([L...;) en niet de componentklasse (JVMS §6.5); anders
                // keurt de verifier de bytecode af.
                owner = BytecodeHelper.toInternalName(receiverType);
            } else {
                owner = BytecodeHelper.toInternalName(mangledOwner);
            }
            functionName = methodName;
        } else {
            // Geen 'Owner_method'-mangling: aanroep naar de eigen klasse.
            owner = ownerInternalName;
        }

        if (isSuperCall(call)) {
            emitSuperCall(call, owner, descriptor);
            return;
        }

        if (isConstructorCall(call)) {
            if (isSelfConstructorCall(call)) {
                // super()/this()-init op een bestaande receiver (%this):
                // alleen receiver + args laden, geen NEW/DUP.
                final var args = call.args();
                emitValue(args.getFirst());
                for (final var arg : args.subList(1, args.size())) {
                    emitValue(arg);
                }
                mv.visitMethodInsn(
                        Opcodes.INVOKESPECIAL,
                        owner,
                        "<init>",
                        descriptor,
                        false
                );
            } else {
                emitConstructorCall(call, owner, descriptor);
            }
            return;
        }

        emitCallOperands(call, opcode);

        mv.visitMethodInsn(
                opcode,
                owner,
                functionName,
                descriptor,
                opcode == Opcodes.INVOKEINTERFACE
        );

        storeResult(call.result());
    }

    /**
     * Emitteert de operands van een call. Gewone calls pushen de args flat;
     * varargs-calls (laatste paramType is een array, en er worden méér args
     * meegegeven dan de declaratie heeft) moeten de staart eerst in een
     * array-object verzamelen vóór de invoke.
     */
    private void emitCallOperands(final IRInstruction.Call call, final int opcode) {
        final var paramTypes = call.paramTypes();
        final var args = call.args();
        final boolean isStatic = opcode == Opcodes.INVOKESTATIC;
        // Varargs: laatste paramType is een array; de staart (inclusief een
        // LEEGE staart) moet altijd in een array-object worden verzameld,
        // anders verwacht de verifier een ontbrekend Object[]-argument.
        final boolean isVarargs = !isStatic
                && !paramTypes.isEmpty()
                && BytecodeHelper.createDescriptor(paramTypes.getLast()).startsWith("[")
                && args.size() >= paramTypes.size();

        if (!isVarargs) {
            args.forEach(this::emitValue);
            return;
        }

        final int fixedArgs = (isStatic ? 0 : 1) + (paramTypes.size() - 1);
        for (int i = 0; i < fixedArgs; i++) {
            emitValue(args.get(i));
        }
        emitArrayValue(
                args.subList(fixedArgs, args.size()),
                BytecodeHelper.createDescriptor(paramTypes.getLast())
        );
    }

    private void emitArrayValue(final List<? extends IRValue> elements, final String arrayDescriptor) {
        final String componentInternalName = arrayDescriptor.startsWith("[L")
                ? arrayDescriptor.substring(2, arrayDescriptor.length() - 1)
                : "java/lang/Object";
        // Reference-arrays (Object[], wrapper-arrays) verlangen een
        // reference per element: primitieven worden geboxed
        // (auto-boxing, JLS �5.1.7).
        mv.visitIntInsn(elements.size() > 127 ? Opcodes.SIPUSH : Opcodes.BIPUSH, elements.size());
        mv.visitTypeInsn(Opcodes.ANEWARRAY, componentInternalName);
        for (int k = 0; k < elements.size(); k++) {
            mv.visitInsn(Opcodes.DUP);
            mv.visitIntInsn(k > 127 ? Opcodes.SIPUSH : Opcodes.BIPUSH, k);
            final var element = elements.get(k);
            emitValue(element);
            if (boxOperand(element.type())) {
                // De waarde is in de wrapper gezet; niets extra's
            }
            mv.visitInsn(Opcodes.AASTORE);
        }
    }

    /**
     * Box de bovenop de stack staande primitieve waarde naar zijn wrapper
     * (auto-boxing). Geeft false terug wanneer er geen primitieve stond.
     */
    private boolean boxOperand(final IRType type) {
        final String boxClass;
        final String methodSignature;
        if (type instanceof IRType.Int(int bits)) {
            boxClass = switch (bits) {
                case 8 -> "java/lang/Byte";
                case 16 -> "java/lang/Short";
                case 64 -> "java/lang/Long";
                default -> "java/lang/Integer";
            };
            methodSignature = switch (bits) {
                case 8 -> "(B)Ljava/lang/Byte;";
                case 16 -> "(S)Ljava/lang/Short;";
                case 64 -> "(J)Ljava/lang/Long;";
                default -> "(I)Ljava/lang/Integer;";
            };
        } else if (type instanceof IRType.Float(int bits)) {
            if (bits == 64) {
                boxClass = "java/lang/Double";
                methodSignature = "(D)Ljava/lang/Double;";
            } else {
                boxClass = "java/lang/Float";
                methodSignature = "(F)Ljava/lang/Float;";
            }
        } else if (type instanceof IRType.Bool) {
            boxClass = "java/lang/Boolean";
            methodSignature = "(Z)Ljava/lang/Boolean;";
        } else {
            return false;
        }
        mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                boxClass,
                "valueOf",
                methodSignature,
                false
        );
        return true;
    }

    /**
     * Super-constructor-aanroep (super() of this.super()): het object bestaat
     * al (this), dus er mag géén NEW/DUP komen. De 'this'-referentie zit al
     * als eerste element in call.args() (impliciet toegevoegd in de IR).
     */
    private void emitSuperCall(final IRInstruction.Call call,
                               final String owner,
                               final String descriptor) {
        call.args().forEach(this::emitValue);

        mv.visitMethodInsn(
                Opcodes.INVOKESPECIAL,
                owner,
                "<init>",
                descriptor,
                false
        );
    }

    private boolean isSuperCall(final IRInstruction.Call call) {
        return call.callKind() == CallKind.SPECIAL
                && call.function().endsWith("_super");
    }

    /**
     * Constructor-aanroep: emitteert de allocatie en de init-aanroep samen.
     * Na de aanroep blijft de referentie op de stack; die wordt in het
     * heapalloc-slot bewaard als de instantie nog wordt gebruikt, anders
     * weggegooid (POP).
     */
    private void emitConstructorCall(final IRInstruction.Call call,
                                     final String owner,
                                     final String descriptor) {
        mv.visitTypeInsn(Opcodes.NEW, owner);
        mv.visitInsn(Opcodes.DUP);

        call.args().forEach(this::emitValue);

        mv.visitMethodInsn(
                Opcodes.INVOKESPECIAL,
                owner,
                "<init>",
                descriptor,
                false
        );

        final var alloc = pendingConstructorAllocs.pollLast();

        if (alloc != null && isReferenced(alloc)) {
            storeResult(alloc);
        } else {
            mv.visitInsn(Opcodes.POP);
        }
    }

    /**
     * Geeft aan of de gegeven heapalloc-resultaat-waarde door een andere
     * instructie in de functie wordt gebruikt. Als dat niet zo is, kan de
     * referentie na de constructor-aanroep weggegooid worden (POP).
     */
    private boolean isReferenced(final IRValue alloc) {
        final var name = IRValue.nameOf(alloc);

        for (final var block : blocks) {
            for (final var instr : block.instructions()) {
                if (referencesValue(name, instr)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean throwInstReferences(final String name,
                                        final IRInstruction.Throw throwInst) {
        return throwInst.result() != null
                && name.equals(IRValue.nameOf(throwInst.result()));
    }

    private boolean referencesValue(final String name,
                                    final IRInstruction instr) {
        return switch (instr) {
            case IRInstruction.BinaryOp b ->
                    referencesValue(name, b.left()) || referencesValue(name, b.right());
            case IRInstruction.Load l -> referencesValue(name, l.ptr());
            case IRInstruction.AllocaArray a -> referencesValue(name, a.size());
            case IRInstruction.ArrayLoad a ->
                    referencesValue(name, a.array()) || referencesValue(name, a.index());
            case IRInstruction.ArrayStore a ->
                    referencesValue(name, a.array())
                            || referencesValue(name, a.index())
                            || referencesValue(name, a.value());
            case IRInstruction.ArrayLength a -> referencesValue(name, a.array());
            case IRInstruction.Store s ->
                    referencesValue(name, s.ptr()) || referencesValue(name, s.value());
            case IRInstruction.Call c -> referencesValue(name, c.args());
            case IRInstruction.IndirectCall c ->
                    referencesValue(name, c.callee()) || referencesValue(name, c.args());
            case IRInstruction.CondBranch c -> referencesValue(name, c.condition());
            case IRInstruction.Return r -> referencesValue(name, r.value());
            case IRInstruction.Throw t -> throwInstReferences(name, t);
            case IRInstruction.Cast c -> referencesValue(name, c.source());
            case IRInstruction.InstanceOf i -> referencesValue(name, i.source());
            case IRInstruction.MonitorEnter m -> referencesValue(name, m.object());
            case IRInstruction.MonitorExit m -> referencesValue(name, m.object());
            case IRInstruction.Move m -> referencesValue(name, m.value());
            case IRInstruction.Phi p -> {
                var referenced = false;
                for (final var incoming : p.incomingValues()) {
                    if (referencesValue(name, incoming.value())) {
                        referenced = true;
                        break;
                    }
                }
                yield referenced;
            }
            default -> false;
        };
    }

    private boolean referencesValue(final String name,
                                    final List<? extends IRValue> values) {
        if (values == null) {
            return false;
        }
        for (final var value : values) {
            if (referencesValue(name, value)) {
                return true;
            }
        }
        return false;
    }

    private boolean referencesValue(final String name,
                                    final IRValue value) {
        return switch (value) {
            case IRValue.Temp temp -> temp.name().equals(name);
            case IRValue.Values values -> referencesValue(name, values.values());
            case null, default -> false;
        };
    }

    private boolean isConstructorCall(final IRInstruction.Call call) {
        return call.function().endsWith("_init")
                && call.returnType() == IRType.VOID
                && call.callKind() == CallKind.SPECIAL;
    }

    /**
     * Constructor-aanroep waarvan de receiver de al-bestaande this is
     * (super()/this() van de eigen constructor). Dan is er geen NEW nodig.
     */
    private boolean isSelfConstructorCall(final IRInstruction.Call call) {
        return !call.args().isEmpty()
                && "%this".equals(IRValue.nameOf(call.args().getFirst()));
    }

    private void emitIndirectCall(final IRInstruction.IndirectCall call) {
        emitValue(call.callee());
        for (final var arg : call.args()) {
            emitValue(arg);
        }
        final var descriptor = BytecodeHelper.createDescriptor(
                call.fnType().paramTypes(),
                call.fnType().returnType()
        );
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/invoke/MethodHandle",
                "invoke",
                descriptor,
                false
        );
        storeResult(call.result());
    }

    private int resolveInvokeOpcode(final CallKind callKind) {
        return switch (callKind) {
            case INTERFACE -> Opcodes.INVOKEINTERFACE;
            case SPECIAL -> Opcodes.INVOKESPECIAL;
            case STATIC -> Opcodes.INVOKESTATIC;
            case VIRTUAL -> Opcodes.INVOKEVIRTUAL;
        };
    }

    // -------------------------------------------------------
    // Control-flow
    // -------------------------------------------------------

    private void emitCondBranch(final IRInstruction.CondBranch condBranch) {
        final var trueLabel = getOrCreateLabel(condBranch.trueLabel());

        if (condBranch.condition() instanceof IRValue.Temp temp
                && pendingComparisons.containsKey(temp.name())) {
            final var binaryOp = pendingComparisons.remove(temp.name());
            emitComparisonOperands(binaryOp);
            mv.visitJumpInsn(resolveJumpOpcode(binaryOp), trueLabel);
        } else {
            emitValue(condBranch.condition());
            mv.visitJumpInsn(Opcodes.IFNE, trueLabel);
        }

        // De false-tak kan niet als fall-through worden aangenomen (bv. bij
        // een back-edge naar een eerder blok): spring expliciet.
        mv.visitJumpInsn(Opcodes.GOTO, getOrCreateLabel(condBranch.falseLabel()));
    }

    /**
     * Emitteert de operanden van een vergelijking, inclusief de
     * benodigde float/long-vergelijking (LCMP/FCMPL/DCMPL).
     */
    private void emitComparisonOperands(final IRInstruction.BinaryOp binaryOp) {
        final var leftType = binaryOp.left().type();

        if (leftType instanceof IRType.Float(int bits)) {
            emitValue(binaryOp.left());
            emitValue(binaryOp.right());
            mv.visitInsn(bits == 64 ? Opcodes.DCMPL : Opcodes.FCMPL);
        } else if (leftType instanceof IRType.Int(int bits) && bits == 64) {
            emitValue(binaryOp.left());
            emitValue(binaryOp.right());
            mv.visitInsn(Opcodes.LCMP);
        } else {
            emitValue(binaryOp.left());
            if (!(binaryOp.right() instanceof IRValue.ConstNull)) {
                emitValue(binaryOp.right());
            }
        }
    }

    /**
     * Materialiseert een vergelijking tot een boolean (0/1) op de stack.
     * Wordt gebruikt wanneer het resultaat van een vergelijking niet
     * door een CondBranch maar door een andere consument wordt gebruikt.
     */
    private void materializeComparison(final IRInstruction.BinaryOp binaryOp) {
        final var trueLabel = new Label();
        final var endLabel = new Label();

        emitComparisonOperands(binaryOp);
        mv.visitJumpInsn(resolveJumpOpcode(binaryOp), trueLabel);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitJumpInsn(Opcodes.GOTO, endLabel);
        mv.visitLabel(trueLabel);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitLabel(endLabel);
    }

    private int resolveJumpOpcode(final IRInstruction.BinaryOp binaryOp) {
        final var leftType = binaryOp.left().type();
        final var right = binaryOp.right();        if (right instanceof IRValue.ConstNull) {
            return switch (binaryOp.op()) {
                case EQ -> Opcodes.IFNULL;
                case NEQ -> Opcodes.IFNONNULL;
                default -> throw new UnsupportedOperationException(
                        "Unsupported null comparison op: " + binaryOp.op());
            };
        }

        if (isReferenceType(leftType)) {
            return switch (binaryOp.op()) {
                case EQ -> Opcodes.IF_ACMPEQ;
                case NEQ -> Opcodes.IF_ACMPNE;
                default -> {
                    System.err.println("[CMP-REF-FAIL-LOC] owner=" + ownerInternalName
                            + " loc=" + binaryOp.location()
                            + " op=" + binaryOp.op()
                            + " leftType=" + leftType
                            + " left=" + binaryOp.left()
                            + " right=" + binaryOp.right());
                    throw new UnsupportedOperationException(
                            "Unsupported comparison op: " + binaryOp.op());
                }
            };
        }

        if (leftType instanceof IRType.Int(int bits) && bits == 64) {
            return switch (binaryOp.op()) {
                case LT -> Opcodes.IFLT;
                case LTE -> Opcodes.IFLE;
                case EQ -> Opcodes.IFEQ;
                case GTE -> Opcodes.IFGE;
                case GT -> Opcodes.IFGT;
                case NEQ -> Opcodes.IFNE;
                default -> throw new UnsupportedOperationException(
                        "Unsupported comparison op: " + binaryOp.op());
            };
        }

        final int fallbackResult = switch (binaryOp.op()) {
            case LT -> Opcodes.IF_ICMPLT;
            case LTE -> Opcodes.IF_ICMPLE;
            case EQ -> Opcodes.IF_ICMPEQ;
            case GTE -> Opcodes.IF_ICMPGE;
            case GT -> Opcodes.IF_ICMPGT;
            case NEQ -> Opcodes.IF_ICMPNE;
            default -> throw new UnsupportedOperationException(
                    "Unsupported comparison op: " + binaryOp.op()
                    + " leftType=" + leftType + " left=" + binaryOp.left()
                    + " right=" + binaryOp.right());
        };
        return fallbackResult;
    }

    private boolean isReferenceType(final IRType type) {
        if (type instanceof IRType.Ptr) {
            return true;
        }
        if (type instanceof IRType.Array) {
            return true;
        }
        return type instanceof IRType.Function;
    }

    // -------------------------------------------------------
    // Waarden
    // -------------------------------------------------------

    private void emitValue(final IRValue value) {
        switch (value) {
            case IRValue.ConstFloat(double floatValue, IRType.Float type) -> {
                if (type.bits() == 32) {
                    mv.visitLdcInsn(Double.valueOf(floatValue).floatValue());
                } else {
                    mv.visitLdcInsn(floatValue);
                }
            }
            case IRValue.ConstBool(boolean b) -> mv.visitInsn(b ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
            case IRValue.ConstNull ignored -> mv.visitInsn(Opcodes.ACONST_NULL);
            case IRValue.ConstInt(long constIntValue, IRType.Int type) -> emitConstInt(constIntValue, type);
            case IRValue.ConstString(String stringValue) -> mv.visitLdcInsn(stringValue);
            case IRValue.ConstUndef ignored -> emitDefaultValue(value.type());
            case IRValue.ConstClass(IRType.Ptr type) -> {
                final var descriptor = type.jvmDescriptor();
                mv.visitLdcInsn(Type.getType(descriptor));
            }
            case IRValue.Temp(String name, IRType type) -> {
                if (pendingComparisons.containsKey(name)) {
                    materializeComparison(pendingComparisons.remove(name));
                    return;
                }
                final var opcode = resolveLoadOpcode(type);
                mv.visitVarInsn(opcode, slots.slotOf(name, type));
            }
            case IRValue.Named named -> emitNamed(named);
            case IRValue.Values(List<IRValue> values) -> values.forEach(this::emitValue);
            case IRValue.FunctionRef(String name, IRType.Function fnType, IRType targetInterface,
                                     String samMethodName, String samDesc, String instDesc,
                                     java.util.List<String> capturedNames) -> emitFunctionRef(name, fnType, targetInterface, capturedNames);
            case null, default -> throw new IllegalStateException(
                    "Unexpected IRValue: " + (value != null ? value.getClass().getSimpleName() : "null"));
        }
    }

    private void emitConstInt(final long constIntValue,
                              final IRType.Int type) {
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

    /**
     * Geeft een default-waarde voor een ongedefinieerde waarde
     * (0 of null), zodat een phi-incoming zonder definitie de stack
     * niet uit balans brengt.
     */
    private void emitDefaultValue(final IRType type) {
        if (type instanceof IRType.Ptr || type instanceof IRType.Array
                || type instanceof IRType.Function) {
            mv.visitInsn(Opcodes.ACONST_NULL);
        } else if (type instanceof IRType.Int(int bits) && bits == 64) {
            mv.visitInsn(Opcodes.LCONST_0);
        } else if (type instanceof IRType.Float(int bits) && bits == 64) {
            mv.visitInsn(Opcodes.DCONST_0);
        } else if (type instanceof IRType.Float) {
            mv.visitInsn(Opcodes.FCONST_0);
        } else {
            mv.visitInsn(Opcodes.ICONST_0);
        }
    }

    private void emitNamed(final IRValue.Named named) {
        final var global = findGlobal(named.name());

        if (isStringLiteral(named, global)) {
            emitValue(global.initializer());
            return;
        }

        if (named.isStatic() || (global != null && global.isStatic())) {
            mv.visitFieldInsn(
                    Opcodes.GETSTATIC,
                    resolveOwnerName(named, global),
                    resolveFieldName(named, global),
                    BytecodeHelper.createDescriptor(named.type())
            );
        } else {
            mv.visitFieldInsn(
                    Opcodes.GETFIELD,
                    BytecodeHelper.toInternalName(named.ownerType()),
                    named.name(),
                    BytecodeHelper.createDescriptor(named.type())
            );
        }
    }

    private void emitFunctionRef(final String name,
                                 final IRType.Function fnType,
                                 final IRType targetInterface,
                                 final java.util.List<String> capturedNames) {
        final var owner = name.contains(".")
                ? name.substring(0, name.lastIndexOf('.')).replace('.', '/')
                : "java/lang/invoke/MethodHandles";
        final var methodName = name.contains(".")
                ? name.substring(name.lastIndexOf('.') + 1)
                : name;
        final var descriptor = BytecodeHelper.createDescriptor(fnType.paramTypes(), fnType.returnType());
        final var handle = new Handle(
                Opcodes.H_INVOKESTATIC,
                owner,
                methodName,
                descriptor,
                false
        );

        if (targetInterface instanceof IRType.Ptr ptr && ptr.jvmDescriptor() != null) {
            emitSamConversion(handle, fnType, ptr.jvmDescriptor(), capturedNames);
        } else {
            mv.visitLdcInsn(handle);
        }
    }

    private void emitSamConversion(final Handle implHandle,
                                   final IRType.Function fnType,
                                   final String interfaceDescriptor,
                                   final java.util.List<String> capturedNames) {
        final var functionalInterface = Type.getType(interfaceDescriptor);
        final var samMethod = findSamMethod(functionalInterface);
        if (samMethod == null) {
            mv.visitLdcInsn(implHandle);
            return;
        }

        final var samMethodName = samMethod.getName();
        final var erasedSamDescriptor = Type.getMethodDescriptor(samMethod);

        final var samParamCount = Type.getArgumentTypes(erasedSamDescriptor).length;
        final var capturedVarCount = fnType.paramTypes().size() - samParamCount;

        final var samMethodType = Type.getMethodType(erasedSamDescriptor);
        final var instantiatedDescriptor = BytecodeHelper.createDescriptor(
                fnType.paramTypes().subList(capturedVarCount, fnType.paramTypes().size()),
                fnType.returnType()
        );
        final var instantiatedMethodType = Type.getMethodType(instantiatedDescriptor);

        final var bsmHandle = new Handle(
                Opcodes.H_INVOKESTATIC,
                "java/lang/invoke/LambdaMetafactory",
                "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
                false
        );

        final var capturedVarTypes = fnType.paramTypes().subList(0, capturedVarCount);
        final var dynamicArgTypes = capturedVarTypes.stream()
                .map(t -> Type.getType(BytecodeHelper.createDescriptor(t)))
                .toArray(Type[]::new);
        final var dynamicType = Type.getMethodType(
                functionalInterface,
                dynamicArgTypes
        );

        for (int i = 0; i < capturedVarCount; i++) {
            final String capturedName;
            if (capturedNames != null && i < capturedNames.size()) {
                capturedName = capturedNames.get(i);
            } else {
                capturedName = "%" + i;
            }
            final var slot = slots.slotOf(capturedName, capturedVarTypes.get(i));
            final var loadOpcode = resolveLoadOpcode(capturedVarTypes.get(i));
            mv.visitVarInsn(loadOpcode, slot);
        }

        mv.visitInvokeDynamicInsn(
                samMethodName,
                dynamicType.getDescriptor(),
                bsmHandle,
                samMethodType,
                implHandle,
                instantiatedMethodType
        );
    }

    private java.lang.reflect.Method findSamMethod(final Type functionalInterface) {
        try {
            final var clazz = Class.forName(
                    functionalInterface.getClassName(), false,
                    Thread.currentThread().getContextClassLoader());
            for (final var m : clazz.getMethods()) {
                if (m.isDefault() || java.lang.reflect.Modifier.isStatic(m.getModifiers()) || m.getDeclaringClass() == Object.class) {
                    continue;
                }
                if (java.lang.reflect.Modifier.isAbstract(m.getModifiers())) {
                    return m;
                }
            }
        } catch (final ClassNotFoundException e) {
            // Fallback: assume Consumer.accept, Predicate.test etc.
        }
        return null;
    }

    // -------------------------------------------------------
    // Global-opzoekingen en namen
    // -------------------------------------------------------

    private IRGlobal findGlobal(final String name) {
        var global = codeEmitter.getGlobal(name);
        if (global == null && !name.startsWith("@")) {
            global = codeEmitter.getGlobal("@" + name);
        }
        if (global == null && name.startsWith("@")) {
            global = codeEmitter.getGlobal(name.substring(1));
        }
        return global;
    }

    private boolean isStringLiteral(final IRValue.Named named,
                                    final IRGlobal global) {
        if (global == null) {
            return false;
        }
        return named.name().startsWith("@.str.")
                && global.initializer() != null;
    }

    private String resolveOwnerName(final IRValue.Named named,
                                    final IRGlobal global) {
        if (global != null && global.ownerType() != null) {
            return BytecodeHelper.toInternalName(global.ownerType());
        }
        if (named.ownerType() != null) {
            return BytecodeHelper.toInternalName(named.ownerType());
        }

        var name = named.name();
        if (name.startsWith("@")) {
            name = name.substring(1);
        }
        final var sepIndex = name.lastIndexOf('_');
        if (sepIndex > -1) {
            name = name.substring(0, sepIndex);
        }
        return BytecodeHelper.toInternalName(name);
    }

    private String resolveFieldName(final IRValue.Named named,
                                    final IRGlobal global) {
        var name = global != null ? global.name() : named.name();
        if (name.startsWith("@")) {
            name = name.substring(1);
        }
        final var sepIndex = name.lastIndexOf('_');
        if (sepIndex > -1) {
            return name.substring(sepIndex + 1);
        }
        return name;
    }

    // -------------------------------------------------------
    // Resultaat opslaan in een slot
    // -------------------------------------------------------

    private void storeResult(final IRValue result) {
        if (result instanceof IRValue.Temp(String name, IRType type)) {
            mv.visitVarInsn(
                    resolveStoreOpcode(type),
                    slots.slotOf(name, type)
            );
        }
    }

    // -------------------------------------------------------
    // Opcode-resolutie
    // -------------------------------------------------------

    private int resolveLoadOpcode(final IRType type) {
        return switch (type) {
            case IRType.Int intType -> switch (intType.bits()) {
                case 8, 16, 32 -> Opcodes.ILOAD;
                case 64 -> Opcodes.LLOAD;
                default -> Opcodes.ILOAD;
            };
            case IRType.Float floatType -> floatType.bits() == 64 ? Opcodes.DLOAD : Opcodes.FLOAD;
            case IRType.Ptr ignored -> Opcodes.ALOAD;
            case IRType.Bool ignored -> Opcodes.ILOAD;
            case IRType.Array ignored -> Opcodes.ALOAD;
            case IRType.Void ignored -> Opcodes.NOP;
            default -> throw new UnsupportedOperationException("Unsupported load type: " + type);
        };
    }

    private int resolveStoreOpcode(final IRType type) {
        return switch (type) {
            case IRType.Int intType -> switch (intType.bits()) {
                case 8, 16, 32 -> Opcodes.ISTORE;
                case 64 -> Opcodes.LSTORE;
                default -> Opcodes.ISTORE;
            };
            case IRType.Float floatType -> floatType.bits() == 64 ? Opcodes.DSTORE : Opcodes.FSTORE;
            case IRType.Ptr ignored -> Opcodes.ASTORE;
            case IRType.Bool ignored -> Opcodes.ISTORE;
            case IRType.Array ignored -> Opcodes.ASTORE;
            case IRType.Void ignored -> Opcodes.NOP;
            default -> throw new UnsupportedOperationException("Unsupported store type: " + type);
        };
    }

    private int resolveReturnOpcode(final IRValue value) {
        return switch (value) {
            case IRValue.ConstFloat ignored -> resolveReturnOpcode(value.type());
            case IRValue.ConstBool ignored -> Opcodes.IRETURN;
            case IRValue.ConstNull ignored -> Opcodes.ARETURN;
            case IRValue.ConstInt ignored -> resolveReturnOpcode(value.type());
            case IRValue.ConstString ignored -> Opcodes.ARETURN;
            case IRValue.ConstUndef ignored -> resolveReturnOpcode(value.type());
            case IRValue.ConstClass ignored -> Opcodes.ARETURN;
            case IRValue.Temp temp -> resolveReturnOpcode(temp.type());
            case IRValue.Named named -> resolveReturnOpcode(named.type());
            case IRValue.Values(List<IRValue> values) -> resolveReturnOpcode(values.getLast());
            case IRValue.FunctionRef ignored -> Opcodes.ARETURN;
        };
    }

    private int resolveReturnOpcode(final IRType type) {
        return switch (type) {
            case IRType.Float floatType -> floatType.bits() == 32 ? Opcodes.FRETURN : Opcodes.DRETURN;
            case IRType.Int intType -> intType.bits() == 64 ? Opcodes.LRETURN : Opcodes.IRETURN;
            case IRType.Ptr ignored -> Opcodes.ARETURN;
            case IRType.Bool ignored -> Opcodes.IRETURN;
            case IRType.Void ignored -> Opcodes.RETURN;
            case IRType.Array ignored -> Opcodes.ARETURN;
            default -> throw new UnsupportedOperationException("Unsupported return type: " + type);
        };
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
            default -> Opcodes.AALOAD;
        };
    }

    private int resolveAddOpcode(final IRValue left) {
        return resolveAddOpcode(left.type());
    }

    private int resolveAddOpcode(final IRType type) {
        return switch (type) {
            case IRType.Int intType -> intType.bits() == 64 ? Opcodes.LADD : Opcodes.IADD;
            case IRType.Float floatType -> floatType.bits() == 32 ? Opcodes.FADD : Opcodes.DADD;
            case IRType.Ptr ptr -> resolveAddOpcode(ptr.pointee());
            default -> throw new UnsupportedOperationException("Unsupported add type: " + type);
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

    private int resolveMulOpcode(final IRValue left) {
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
            case IRType.Bool ignored -> Opcodes.IAND;
            default -> throw new UnsupportedOperationException("Unsupported and type: " + left.type());
        };
    }

    private int resolveOrOpcode(final IRValue left) {
        return switch (left.type()) {
            case IRType.Int intType -> intType.bits() == 64 ? Opcodes.LOR : Opcodes.IOR;
            case IRType.Bool ignored -> Opcodes.IOR;
            default -> throw new UnsupportedOperationException("Unsupported or type: " + left.type());
        };
    }

    private int resolveXorOpcode(final IRValue left) {
        return switch (left.type()) {
            case IRType.Int intType -> intType.bits() == 64 ? Opcodes.LXOR : Opcodes.IXOR;
            case IRType.Bool ignored -> Opcodes.IXOR;
            default -> throw new UnsupportedOperationException("Unsupported xor type: " + left.type());
        };
    }

    private int resolveBitAndOpcode(final IRValue left) {
        return switch (left.type()) {
            case IRType.Int intType -> intType.bits() == 64 ? Opcodes.LAND : Opcodes.IAND;
            case IRType.Bool ignored -> Opcodes.IAND;
            default -> throw new UnsupportedOperationException("Unsupported bitand type: " + left.type());
        };
    }

    private int resolveBitOrOpcode(final IRValue left) {
        return switch (left.type()) {
            case IRType.Int intType -> intType.bits() == 64 ? Opcodes.LOR : Opcodes.IOR;
            case IRType.Bool ignored -> Opcodes.IOR;
            default -> throw new UnsupportedOperationException("Unsupported bitor type: " + left.type());
        };
    }

    private int resolveBitXorOpcode(final IRValue left) {
        return switch (left.type()) {
            case IRType.Int intType -> intType.bits() == 64 ? Opcodes.LXOR : Opcodes.IXOR;
            case IRType.Bool ignored -> Opcodes.IXOR;
            default -> throw new UnsupportedOperationException("Unsupported bitxor type: " + left.type());
        };
    }

    // -------------------------------------------------------
    // Lokale variabelen (debuginfo)
    // -------------------------------------------------------

    public void visitLocalVariables(final IRFunction function,
                                    final String startLabelName) {
        final var start = getOrCreateLabel(startLabelName);
        final var end = getOrCreateLabel("END");
        final var allocaVersions = function.allocaVersions();
        // JVMS: identieke (name, slot)-entries in de LocalVariableTable
        // maken de classfile ongeldig ("Duplicated LocalVariableTable
        // attribute entry"). Hergebruikte namen (bv. `ex` in meerdere
        // catch-blokken) én slot-overlap mogen per (naam, slot) maar één
        // keer geëmitteerd worden; de eerste definitie wint.
        final var seenLocalVars = new HashSet<String>();

        for (final var param : function.params) {
            if (param instanceof IRValue.Temp(String name, IRType type)) {
                final var paramName = SlotAllocator.normalize(name);
                final var index = slots.getSlot(name);
                final var paramDescriptor = BytecodeHelper.createDescriptor(type);
                if (!seenLocalVars.add(paramName + ":" + index)) {
                    continue;
                }

                mv.visitLocalVariable(
                        paramName,
                        paramDescriptor,
                        null,
                        start,
                        end,
                        index
                );
            }
        }

        for (final var localVar : function.localVariables()) {
            final var tempName = localVar.allocaTemp().name();

            // Probeer eerst het alloca-temp zelf (overleeft als het nog gebruikt wordt)
            final var slotName = slots.hasSlot(tempName)
                    ? tempName
                    : allocaVersions.get(tempName);

            if (slotName == null || !slots.hasSlot(slotName)) continue;

            final var index = slots.getSlot(slotName);
            final var descriptor = BytecodeHelper.createDescriptor(localVar.type());
            if (!seenLocalVars.add(localVar.sourceName() + ":" + index)) {
                continue;
            }

            mv.visitLocalVariable(
                    localVar.sourceName(),
                    descriptor,
                    null,
                    start,
                    end,
                    index
            );
        }
    }
}

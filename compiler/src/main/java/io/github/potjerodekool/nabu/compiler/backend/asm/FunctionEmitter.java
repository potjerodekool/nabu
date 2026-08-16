package io.github.potjerodekool.nabu.compiler.backend.asm;

import io.github.potjerodekool.nabu.compiler.debug.SourceLocation;
import io.github.potjerodekool.nabu.compiler.ir.CallKind;
import io.github.potjerodekool.nabu.compiler.ir.IRBasicBlock;
import io.github.potjerodekool.nabu.compiler.ir.IRFunction;
import io.github.potjerodekool.nabu.compiler.ir.IRGlobal;
import io.github.potjerodekool.nabu.compiler.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.compiler.ir.types.IRType;
import io.github.potjerodekool.nabu.compiler.ir.values.IRValue;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
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
            case IRInstruction.Branch branch -> {
                mv.visitJumpInsn(Opcodes.GOTO, getOrCreateLabel(branch.targetLabel()));
            }
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
        final var type = AsmHelper.toInternalName(throwInst.type());
        mv.visitTypeInsn(Opcodes.NEW, type);
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>", "()V", false);
        mv.visitInsn(Opcodes.ATHROW);
    }

    private void emitInstanceOf(final IRInstruction.InstanceOf instanceOf) {
        emitValue(instanceOf.source());
        mv.visitTypeInsn(Opcodes.INSTANCEOF, AsmHelper.toInternalName(instanceOf.type()));
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
            if (global != null && global.initializer() instanceof IRValue.ConstString constString) {
                template.append(constString.value());
                return;
            }
        }
        template.append("\u0001");
    }

    private void generateDescriptorForStringConcat(final IRInstruction.BinaryOp binaryOp,
                                                   final StringBuilder stringBuilder) {
        if (binaryOp.left() instanceof IRValue.Temp) {
            stringBuilder.append(AsmHelper.createDescriptor(binaryOp.left().type()));
        }
        if (binaryOp.right() instanceof IRValue.Temp) {
            stringBuilder.append(AsmHelper.createDescriptor(binaryOp.right().type()));
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

        if (ptr instanceof IRValue.Named named) {
            final var global = findGlobal(named.name());

            if (isStringLiteral(named, global)) {
                emitValue(global.initializer());
            } else if (named.isStatic() || (global != null && global.isStatic())) {
                mv.visitFieldInsn(
                        Opcodes.GETSTATIC,
                        resolveOwnerName(named, global),
                        resolveFieldName(named, global),
                        AsmHelper.createDescriptor(load.result().type())
                );
            } else {
                mv.visitFieldInsn(
                        Opcodes.GETFIELD,
                        AsmHelper.toInternalName(named.ownerType()),
                        named.name(),
                        AsmHelper.createDescriptor(load.type())
                );
            }
        } else if (ptr instanceof IRValue.Temp temp) {
            final var opcode = resolveLoadOpcode(load.result().type());
            mv.visitVarInsn(opcode, slots.slotOf(temp.name(), temp.type()));
        } else if (ptr instanceof IRValue.Values values) {
            values.values().forEach(this::emitValue);
        } else {
            throw new UnsupportedOperationException("Unexpected load target: " + load);
        }

        storeResult(load.result());
    }

    private void emitStore(final IRInstruction.Store store) {
        if (store.ptr() instanceof IRValue.Named named) {
            final var global = findGlobal(named.name());
            final var isStatic = named.isStatic() || (global != null && global.isStatic());

            emitValue(store.value());

            if (isStatic) {
                mv.visitFieldInsn(
                        Opcodes.PUTSTATIC,
                        resolveOwnerName(named, global),
                        resolveFieldName(named, global),
                        AsmHelper.createDescriptor(
                                global != null ? global.type() : named.type())
                );
            } else {
                mv.visitFieldInsn(
                        Opcodes.PUTFIELD,
                        AsmHelper.toInternalName(named.ownerType()),
                        named.name(),
                        AsmHelper.createDescriptor(named.type())
                );
            }
        } else if (store.ptr() instanceof IRValue.Values values) {
            // Instantieveld: eerst de receiver, dan de waarde.
            values.values().forEach(this::emitValue);
            emitValue(store.value());

            final var named = (IRValue.Named) values.values().getLast();
            mv.visitFieldInsn(
                    Opcodes.PUTFIELD,
                    AsmHelper.toInternalName(named.ownerType()),
                    named.name(),
                    AsmHelper.createDescriptor(named.type())
            );
        } else if (store.ptr() instanceof IRValue.Temp temp) {
            emitValue(store.value());
            mv.visitVarInsn(
                    resolveStoreOpcode(store.value().type()),
                    slots.slotOf(temp.name(), temp.type())
            );
        } else {
            throw new UnsupportedOperationException("Unexpected store target: " + store);
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
        final var internalName = AsmHelper.createDescriptor(allocaArray.allocType());
        mv.visitTypeInsn(Opcodes.ANEWARRAY, internalName);
        storeResult(allocaArray.result());
    }

    private void emitArrayLoad(final IRInstruction.ArrayLoad arrayLoad) {
        emitValue(arrayLoad.array());
        emitValue(arrayLoad.index());
        mv.visitInsn(resolveArrayLoadOpcode(arrayLoad.elemType()));
        storeResult(arrayLoad.result());
    }

    private void emitCast(final IRInstruction.Cast cast) {
        emitValue(cast.source());
        mv.visitTypeInsn(Opcodes.CHECKCAST, AsmHelper.toInternalName(cast.targetType()));
        storeResult(cast.result());
    }

    // -------------------------------------------------------
    // Aanroepen
    // -------------------------------------------------------

    private void emitCall(final IRInstruction.Call call) {
        final var opcode = resolveInvokeOpcode(call.callKind());
        final var descriptor = AsmHelper.createDescriptor(
                call.paramTypes(),
                call.returnType()
        );

        var functionName = call.function();
        final var sepIndex = functionName.lastIndexOf('_');
        final String owner;
        if (sepIndex > -1) {
            owner = AsmHelper.toInternalName(functionName.substring(0, sepIndex).replace('_', '.'));
            functionName = functionName.substring(sepIndex + 1);
        } else {
            // Geen 'Owner_method'-mangling: aanroep naar de eigen klasse.
            owner = ownerInternalName;
        }

        if (isSuperCall(call)) {
            emitSuperCall(call, owner, descriptor);
            return;
        }

        if (isConstructorCall(call)) {
            emitConstructorCall(call, owner, descriptor);
            return;
        }

        call.args().forEach(this::emitValue);

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

    private boolean referencesValue(final String name,
                                    final IRInstruction instr) {
        return switch (instr) {
            case IRInstruction.BinaryOp b ->
                    referencesValue(name, b.left()) || referencesValue(name, b.right());
            case IRInstruction.Load l -> referencesValue(name, l.ptr());
            case IRInstruction.AllocaArray a -> referencesValue(name, a.size());
            case IRInstruction.ArrayLoad a ->
                    referencesValue(name, a.array()) || referencesValue(name, a.index());
            case IRInstruction.ArrayLength a -> referencesValue(name, a.array());
            case IRInstruction.Store s ->
                    referencesValue(name, s.ptr()) || referencesValue(name, s.value());
            case IRInstruction.Call c -> referencesValue(name, c.args());
            case IRInstruction.IndirectCall c ->
                    referencesValue(name, c.callee()) || referencesValue(name, c.args());
            case IRInstruction.CondBranch c -> referencesValue(name, c.condition());
            case IRInstruction.Return r -> referencesValue(name, r.value());
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
        if (value == null) {
            return false;
        }
        if (value instanceof IRValue.Temp temp) {
            return temp.name().equals(name);
        }
        if (value instanceof IRValue.Values values) {
            return referencesValue(name, values.values());
        }
        return false;
    }

    private boolean isConstructorCall(final IRInstruction.Call call) {
        return call.function().endsWith("_init")
                && call.returnType() == IRType.VOID
                && call.callKind() == CallKind.SPECIAL;
    }

    private void emitIndirectCall(final IRInstruction.IndirectCall call) {
        emitValue(call.callee());
        for (final var arg : call.args()) {
            emitValue(arg);
        }
        final var descriptor = AsmHelper.createDescriptor(
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

        if (leftType instanceof IRType.Float floatType) {
            emitValue(binaryOp.left());
            emitValue(binaryOp.right());
            mv.visitInsn(floatType.bits() == 64 ? Opcodes.DCMPL : Opcodes.FCMPL);
        } else if (leftType instanceof IRType.Int intType && intType.bits() == 64) {
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
        final var right = binaryOp.right();

        if (isReferenceType(leftType)) {
            return switch (binaryOp.op()) {
                case EQ -> right instanceof IRValue.ConstNull
                        ? Opcodes.IFNULL : Opcodes.IF_ACMPEQ;
                case NEQ -> right instanceof IRValue.ConstNull
                        ? Opcodes.IFNONNULL : Opcodes.IF_ACMPNE;
                default -> throw new UnsupportedOperationException(
                        "Unsupported comparison op: " + binaryOp.op());
            };
        }

        if (leftType instanceof IRType.Int intType && intType.bits() == 64) {
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

        return switch (binaryOp.op()) {
            case LT -> Opcodes.IF_ICMPLT;
            case LTE -> Opcodes.IF_ICMPLE;
            case EQ -> Opcodes.IF_ICMPEQ;
            case GTE -> Opcodes.IF_ICMPGE;
            case GT -> Opcodes.IF_ICMPGT;
            case NEQ -> Opcodes.IF_ICMPNE;
            default -> throw new UnsupportedOperationException(
                    "Unsupported comparison op: " + binaryOp.op());
        };
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
            case IRValue.FunctionRef(String name, IRType.Function fnType) -> emitFunctionRef(name, fnType);
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
        } else if (type instanceof IRType.Int intType && intType.bits() == 64) {
            mv.visitInsn(Opcodes.LCONST_0);
        } else if (type instanceof IRType.Float floatType && floatType.bits() == 64) {
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
                    AsmHelper.createDescriptor(named.type())
            );
        } else {
            mv.visitFieldInsn(
                    Opcodes.GETFIELD,
                    AsmHelper.toInternalName(named.ownerType()),
                    named.name(),
                    AsmHelper.createDescriptor(named.type())
            );
        }
    }

    private void emitFunctionRef(final String name,
                                 final IRType.Function fnType) {
        final var owner = name.contains(".")
                ? name.substring(0, name.lastIndexOf('.')).replace('.', '/')
                : "java/lang/invoke/MethodHandles";
        final var methodName = name.contains(".")
                ? name.substring(name.lastIndexOf('.') + 1)
                : name;
        final var descriptor = AsmHelper.createDescriptor(fnType.paramTypes(), fnType.returnType());
        final var handle = new Handle(
                Opcodes.H_INVOKESTATIC,
                owner,
                methodName,
                descriptor,
                false
        );
        mv.visitLdcInsn(handle);
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
            return AsmHelper.toInternalName(global.ownerType());
        }
        if (named.ownerType() != null) {
            return AsmHelper.toInternalName(named.ownerType());
        }

        var name = named.name();
        if (name.startsWith("@")) {
            name = name.substring(1);
        }
        final var sepIndex = name.lastIndexOf('_');
        if (sepIndex > -1) {
            name = name.substring(0, sepIndex);
        }
        return AsmHelper.toInternalName(name);
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
        if (result instanceof IRValue.Temp temp) {
            mv.visitVarInsn(
                    resolveStoreOpcode(temp.type()),
                    slots.slotOf(temp.name(), temp.type())
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
            default -> throw new UnsupportedOperationException(
                    "Cannot resolve return opcode for: " + value.getClass().getSimpleName());
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

    private int resolveBitAndOpcode(final IRValue left) {
        return switch (left.type()) {
            case IRType.Int intType -> intType.bits() == 64 ? Opcodes.LAND : Opcodes.IAND;
            default -> throw new UnsupportedOperationException("Unsupported bitand type: " + left.type());
        };
    }

    private int resolveBitOrOpcode(final IRValue left) {
        return switch (left.type()) {
            case IRType.Int intType -> intType.bits() == 64 ? Opcodes.LOR : Opcodes.IOR;
            default -> throw new UnsupportedOperationException("Unsupported bitor type: " + left.type());
        };
    }

    private int resolveBitXorOpcode(final IRValue left) {
        return switch (left.type()) {
            case IRType.Int intType -> intType.bits() == 64 ? Opcodes.LXOR : Opcodes.IXOR;
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

        for (final var param : function.params) {
            if (param instanceof IRValue.Temp temp) {
                final var paramName = SlotAllocator.normalize(temp.name());
                final var index = slots.getSlot(temp.name());
                final var paramDescriptor = AsmHelper.createDescriptor(temp.type());

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
    }
}

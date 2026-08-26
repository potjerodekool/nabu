package io.github.potjerodekool.nabu.compiler.backend.java;

import io.github.potjerodekool.nabu.compiler.backend.jvm.BytecodeHelper;
import io.github.potjerodekool.nabu.compiler.backend.jvm.SlotAllocator;
import io.github.potjerodekool.nabu.compiler.debug.SourceLocation;
import io.github.potjerodekool.nabu.compiler.ir.CallKind;
import io.github.potjerodekool.nabu.compiler.ir.IRBasicBlock;
import io.github.potjerodekool.nabu.compiler.ir.IRFunction;
import io.github.potjerodekool.nabu.compiler.ir.IRGlobal;
import io.github.potjerodekool.nabu.compiler.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.compiler.ir.types.IRType;
import io.github.potjerodekool.nabu.compiler.ir.values.IRValue;

import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Label;
import java.lang.classfile.Opcode;
import java.lang.constant.ClassDesc;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.constant.DynamicCallSiteDesc;
import java.lang.constant.MethodHandleDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Emitteert de instructies van één functie naar bytecode met behulp van de
 * Java 24 ClassFile API ({@link CodeBuilder}).
 *
 * Alle SSA-waarden worden via JVM-local-variable-slots geadresseerd
 * (zie {@link SlotAllocator}): elke instructie die een waarde produceert
 * slaat die op in het slot van het resultaat, en elke referentie naar
 * een waarde laadt uit het bijbehorende slot.
 */
class JavaFunctionEmitter {

    private final CodeBuilder code;
    private final ClassFileByteCodeEmitter codeEmitter;
    private final SlotAllocator slots;
    private final String ownerInternalName;
    private final List<IRBasicBlock> blocks;
    private final Map<String, Label> labels = new HashMap<>();

    private final Map<String, IRInstruction.BinaryOp> pendingComparisons = new HashMap<>();
    private final Deque<IRValue> pendingConstructorAllocs = new ArrayDeque<>();
    private int lastLine = -1;
    private Label currentLabel = null;

    JavaFunctionEmitter(final ClassFileByteCodeEmitter codeEmitter,
                        final CodeBuilder code,
                        final SlotAllocator slots,
                        final String ownerInternalName,
                        final List<IRBasicBlock> blocks) {
        this.code = code;
        this.codeEmitter = codeEmitter;
        this.slots = slots;
        this.ownerInternalName = ownerInternalName;
        this.blocks = blocks;
    }

    // -------------------------------------------------------
    // Blokken en labels
    // -------------------------------------------------------

    void emitBlock(final IRBasicBlock block) {
        visitLabel(block.label());
    }

    void visitLabel(final String labelName) {
        final var label = getOrCreateLabel(labelName);
        code.labelBinding(label);
        currentLabel = label;
    }

    Label getOrCreateLabel(final String blockLabel) {
        return labels.computeIfAbsent(SlotAllocator.normalize(blockLabel), k -> code.newLabel());
    }

    // -------------------------------------------------------
    // Instructies
    // -------------------------------------------------------

    void emit(final IRInstruction instr) {
        maybeVisitLineNumber(instr);

        switch (instr) {
            case IRInstruction.Return returnInst -> emitReturn(returnInst);
            case IRInstruction.BinaryOp binaryOp -> emitBinOp(binaryOp);
            case IRInstruction.Alloca ignored -> {
                // Alloca is alleen een naam; het slot wordt lui toegekend
                // bij de eerste referentie naar de pointer.
            }
            case IRInstruction.Store store -> emitStore(store);
            case IRInstruction.Load load -> emitLoad(load);
            case IRInstruction.Call call -> emitCall(call);
            case IRInstruction.AllocaArray allocArray -> emitNewArray(allocArray);
            case IRInstruction.Branch branch ->
                    code.goto_(getOrCreateLabel(branch.targetLabel()));
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
                code.arraylength();
                storeResult(arrayLength.result());
            }
            case IRInstruction.MonitorEnter monitorEnter -> {
                emitValue(monitorEnter.object());
                code.monitorenter();
            }
            case IRInstruction.MonitorExit monitorExit -> {
                emitValue(monitorExit.object());
                code.monitorexit();
            }
            case IRInstruction.TryCatchRegion ignored -> {
                // TryCatchRegion is metadata; de try-catch-blokken worden
                // door emitTryCatchRegions geregistreerd.
            }
        }
    }

    /**
     * Registreert alle TryCatchRegion-instructies als exception-tabel-regels.
     * Moet worden aangeroepen nadat alle blokken geëmitteerd zijn zodat de
     * labels gebonden zijn.
     */
    void emitTryCatchRegions(final List<IRBasicBlock> linearizedBlocks) {
        for (final var block : linearizedBlocks) {
            for (final var instr : block.instructions()) {
                if (instr instanceof IRInstruction.TryCatchRegion tc) {
                    final var startLabel = getOrCreateLabel(tc.tryStartLabel());
                    final var endLabel = getOrCreateLabel(tc.tryEndLabel());
                    final var handlerLabel = getOrCreateLabel(tc.handlerLabel());
                    final ClassDesc exceptionType = tc.exceptionType() != null
                            ? ClassDesc.ofInternalName(BytecodeHelper.toInternalName(tc.exceptionType()))
                            : null;
                    code.exceptionCatch(startLabel, endLabel, handlerLabel, exceptionType);
                }
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

        code.lineNumber(instr.location().line());
        lastLine = instr.location().line();
    }

    private void emitThrow(final IRInstruction.Throw throwInst) {
        final var type = BytecodeHelper.toInternalName(throwInst.type());
        code.new_(ClassDesc.ofInternalName(type));
        code.dup();
        code.invokespecial(
                ClassDesc.ofInternalName(type),
                "<init>",
                MethodTypeDesc.ofDescriptor("()V")
        );
        code.athrow();
    }

    private void emitInstanceOf(final IRInstruction.InstanceOf instanceOf) {
        emitValue(instanceOf.source());
        code.instanceOf(ClassDesc.ofInternalName(BytecodeHelper.toInternalName(instanceOf.type())));
        storeResult(instanceOf.result());
    }

    private void emitMove(final IRInstruction.Move move) {
        emitValue(move.value());
        storeResult(move.result());
    }

    private void emitReturn(final IRInstruction.Return returnInst) {
        if (returnInst.value() != null) {
            final var value = returnInst.value();
            emitValue(value);
            code.return_(loadStoreKind(returnedType(value)));
        } else {
            code.return_();
        }
    }

    private static IRType returnedType(final IRValue value) {
        return value instanceof IRValue.Values values
                ? values.values().getLast().type()
                : value.type();
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

        emitOpcode(resolveArithmeticOpcode(binaryOp));
        storeResult(binaryOp.result());
    }

    private boolean isComparison(final IRInstruction.BinaryOp.Op op) {
        return switch (op) {
            case EQ, NEQ, LT, LTE, GT, GTE -> true;
            default -> false;
        };
    }

    private Opcode resolveArithmeticOpcode(final IRInstruction.BinaryOp binaryOp) {
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

        final var handle = methodHandleDesc(
                DirectMethodHandleDesc.Kind.STATIC,
                "java/lang/invoke/StringConcatFactory",
                "makeConcatWithConstants",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;"
        );

        if (binaryOp.left() instanceof IRValue.Temp) {
            emitValue(binaryOp.left());
        }
        if (binaryOp.right() instanceof IRValue.Temp) {
            emitValue(binaryOp.right());
        }

        code.invokedynamic(
                DynamicCallSiteDesc.of(
                        handle,
                        "makeConcatWithConstants",
                        MethodTypeDesc.ofDescriptor(descriptor.toString()),
                        template.toString()
                )
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
        template.append('\u0001');
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

        if (ptr instanceof IRValue.Named named) {
            final var global = findGlobal(named.name());

            if (isStringLiteral(named, global)) {
                emitValue(global.initializer());
            } else if (named.isStatic() || (global != null && global.isStatic())) {
                code.getstatic(
                        ClassDesc.ofInternalName(resolveOwnerName(named, global)),
                        resolveFieldName(named, global),
                        ClassDesc.ofDescriptor(BytecodeHelper.createDescriptor(load.result().type()))
                );
            } else {
                code.getfield(
                        ClassDesc.ofInternalName(BytecodeHelper.toInternalName(named.ownerType())),
                        named.name(),
                        ClassDesc.ofDescriptor(BytecodeHelper.createDescriptor(load.type()))
                );
            }
        } else if (ptr instanceof IRValue.Temp temp) {
            load(loadStoreKind(load.result().type()), slots.slotOf(temp.name(), temp.type()));
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
                code.putstatic(
                        ClassDesc.ofInternalName(resolveOwnerName(named, global)),
                        resolveFieldName(named, global),
                        ClassDesc.ofDescriptor(BytecodeHelper.createDescriptor(
                                global != null ? global.type() : named.type()))
                );
            } else {
                code.putfield(
                        ClassDesc.ofInternalName(BytecodeHelper.toInternalName(named.ownerType())),
                        named.name(),
                        ClassDesc.ofDescriptor(BytecodeHelper.createDescriptor(named.type()))
                );
            }
        } else if (store.ptr() instanceof IRValue.Values values) {
            // Emit alleen de receiver-chain (alle elementen behalve de laatste
            // Named field-metadata). De Named wordt alleen gebruikt voor de
            // veldnaam/-descriptor van PUTFIELD.
            final var named = (IRValue.Named) values.values().getLast();
            values.values().subList(0, values.values().size() - 1).forEach(this::emitValue);
            emitValue(store.value());

            code.putfield(
                    ClassDesc.ofInternalName(BytecodeHelper.toInternalName(named.ownerType())),
                    named.name(),
                    ClassDesc.ofDescriptor(BytecodeHelper.createDescriptor(named.type()))
            );
        } else if (store.ptr() instanceof IRValue.Temp temp) {
            emitValue(store.value());
            store(loadStoreKind(store.value().type()), slots.slotOf(temp.name(), temp.type()));
        } else {
            throw new UnsupportedOperationException("Unexpected store target: " + store);
        }
    }

    /**
     * Heap-allocatie voor een nieuw object. De eigenlijke NEW+DUP wordt pas
     * bij de constructor-aanroep geëmitteerd, omdat de referentie daar op de
     * stack nodig is. Hier wordt alleen het resultaat-slot geregistreerd.
     */
    private void emitHeapAlloc(final IRInstruction.HeapAlloc heapAlloc) {
        pendingConstructorAllocs.addLast(heapAlloc.result());
    }

    private void emitNewArray(final IRInstruction.AllocaArray allocaArray) {
        emitValue(allocaArray.size());
        // anewarray verwacht het componenttype; allocType kan zelf al een
        // arraytype zijn (Ptr/Array), leidt dan het component af.
        final var componentType = switch (allocaArray.allocType()) {
            case IRType.Array a -> a.elem();
            case IRType.Ptr p -> p.pointee();
            case IRType t -> t;
        };
        code.anewarray(toClassDesc(BytecodeHelper.toInternalName(componentType)));
        storeResult(allocaArray.result());
    }

    private static ClassDesc toClassDesc(final String internalNameOrDescriptor) {
        if (internalNameOrDescriptor.startsWith("[")
                || (internalNameOrDescriptor.startsWith("L") && internalNameOrDescriptor.endsWith(";"))) {
            return ClassDesc.ofDescriptor(internalNameOrDescriptor);
        }
        return ClassDesc.ofInternalName(internalNameOrDescriptor);
    }

    private void emitArrayLoad(final IRInstruction.ArrayLoad arrayLoad) {
        emitValue(arrayLoad.array());
        emitValue(arrayLoad.index());
        emitOpcode(resolveArrayLoadOpcode(arrayLoad.elemType()));
        storeResult(arrayLoad.result());
    }

    private void emitArrayStore(final IRInstruction.ArrayStore arrayStore) {
        emitValue(arrayStore.array());
        emitValue(arrayStore.index());
        emitValue(arrayStore.value());
        code.arrayStore(loadStoreKind(arrayStore.elemType()));
    }

    private void emitCast(final IRInstruction.Cast cast) {
        emitValue(cast.source());
        code.checkcast(toClassDesc(BytecodeHelper.toInternalName(cast.targetType())));
        storeResult(cast.result());
    }

    // -------------------------------------------------------
    // Aanroepen
    // -------------------------------------------------------

    private void emitCall(final IRInstruction.Call call) {
        final var opcode = resolveInvokeKind(call.callKind());
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
            emitConstructorCall(call, owner, descriptor);
            return;
        }

        call.args().forEach(this::emitValue);

        invoke(opcode, toClassDesc(owner), functionName,
                MethodTypeDesc.ofDescriptor(descriptor));

        storeResult(call.result());
    }

    private void invoke(final DirectMethodHandleDesc.Kind kind,
                        final ClassDesc owner,
                        final String methodName,
                        final MethodTypeDesc type) {
        switch (kind) {
            case VIRTUAL -> code.invokevirtual(owner, methodName, type);
            case SPECIAL -> code.invokespecial(owner, methodName, type);
            case STATIC, INTERFACE_STATIC -> code.invokestatic(owner, methodName, type);
            case INTERFACE_VIRTUAL, INTERFACE_SPECIAL -> code.invokeinterface(owner, methodName, type);
            default -> throw new UnsupportedOperationException("Unsupported invoke kind: " + kind);
        }
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

        code.invokespecial(
                ClassDesc.ofInternalName(owner),
                "<init>",
                MethodTypeDesc.ofDescriptor(descriptor)
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
        code.new_(ClassDesc.ofInternalName(owner));
        code.dup();

        call.args().forEach(this::emitValue);

        code.invokespecial(
                ClassDesc.ofInternalName(owner),
                "<init>",
                MethodTypeDesc.ofDescriptor(descriptor)
        );

        final var alloc = pendingConstructorAllocs.pollLast();

        if (alloc != null && isReferenced(alloc)) {
            storeResult(alloc);
        } else {
            code.pop();
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
        final var descriptor = BytecodeHelper.createDescriptor(
                call.fnType().paramTypes(),
                call.fnType().returnType()
        );
        code.invokevirtual(
                ClassDesc.ofInternalName("java/lang/invoke/MethodHandle"),
                "invoke",
                MethodTypeDesc.ofDescriptor(descriptor)
        );
        storeResult(call.result());
    }

    private DirectMethodHandleDesc.Kind resolveInvokeKind(final CallKind callKind) {
        return switch (callKind) {
            case INTERFACE -> DirectMethodHandleDesc.Kind.INTERFACE_VIRTUAL;
            case SPECIAL -> DirectMethodHandleDesc.Kind.SPECIAL;
            case STATIC -> DirectMethodHandleDesc.Kind.STATIC;
            case VIRTUAL -> DirectMethodHandleDesc.Kind.VIRTUAL;
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
            code.branch(resolveJumpOpcode(binaryOp), trueLabel);
        } else {
            emitValue(condBranch.condition());
            code.branch(Opcode.IFNE, trueLabel);
        }

        // De false-tak kan niet als fall-through worden aangenomen (bv. bij
        // een back-edge naar een eerder blok): spring expliciet.
        code.goto_(getOrCreateLabel(condBranch.falseLabel()));
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
            emitOpcode(floatType.bits() == 64 ? Opcode.DCMPL : Opcode.FCMPL);
        } else if (leftType instanceof IRType.Int intType && intType.bits() == 64) {
            emitValue(binaryOp.left());
            emitValue(binaryOp.right());
            emitOpcode(Opcode.LCMP);
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
        final var trueLabel = code.newLabel();
        final var endLabel = code.newLabel();

        emitComparisonOperands(binaryOp);
        code.branch(resolveJumpOpcode(binaryOp), trueLabel);
        code.iconst_0();
        code.goto_(endLabel);
        code.labelBinding(trueLabel);
        code.iconst_1();
        code.labelBinding(endLabel);
    }

    private Opcode resolveJumpOpcode(final IRInstruction.BinaryOp binaryOp) {
        final var leftType = binaryOp.left().type();
        final var right = binaryOp.right();

        if (right instanceof IRValue.ConstNull) {
            return switch (binaryOp.op()) {
                case EQ -> Opcode.IFNULL;
                case NEQ -> Opcode.IFNONNULL;
                default -> throw new UnsupportedOperationException(
                        "Unsupported null comparison op: " + binaryOp.op());
            };
        }

        if (isReferenceType(leftType)) {
            return switch (binaryOp.op()) {
                case EQ -> Opcode.IF_ACMPEQ;
                case NEQ -> Opcode.IF_ACMPNE;
                default -> throw new UnsupportedOperationException(
                        "Unsupported comparison op: " + binaryOp.op());
            };
        }

        if (leftType instanceof IRType.Int intType && intType.bits() == 64) {
            return switch (binaryOp.op()) {
                case LT -> Opcode.IFLT;
                case LTE -> Opcode.IFLE;
                case EQ -> Opcode.IFEQ;
                case GTE -> Opcode.IFGE;
                case GT -> Opcode.IFGT;
                case NEQ -> Opcode.IFNE;
                default -> throw new UnsupportedOperationException(
                        "Unsupported comparison op: " + binaryOp.op());
            };
        }

        return switch (binaryOp.op()) {
            case LT -> Opcode.IF_ICMPLT;
            case LTE -> Opcode.IF_ICMPLE;
            case EQ -> Opcode.IF_ICMPEQ;
            case GTE -> Opcode.IF_ICMPGE;
            case GT -> Opcode.IF_ICMPGT;
            case NEQ -> Opcode.IF_ICMPNE;
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
                    code.loadConstant(Double.valueOf(floatValue).floatValue());
                } else {
                    code.loadConstant(floatValue);
                }
            }
            case IRValue.ConstBool(boolean b) -> {
                if (b) {
                    code.iconst_1();
                } else {
                    code.iconst_0();
                }
            }
            case IRValue.ConstNull ignored -> code.aconst_null();
            case IRValue.ConstInt(long constIntValue, IRType.Int type) -> emitConstInt(constIntValue, type);
            case IRValue.ConstString(String stringValue) -> code.loadConstant(stringValue);
            case IRValue.ConstUndef ignored -> emitDefaultValue(value.type());
            case IRValue.ConstClass(IRType.Ptr type) -> {
                final var descriptor = type.jvmDescriptor();
                code.loadConstant(toClassDesc(descriptor));
            }
            case IRValue.Temp(String name, IRType type) -> {
                if (pendingComparisons.containsKey(name)) {
                    materializeComparison(pendingComparisons.remove(name));
                    return;
                }
                load(loadStoreKind(type), slots.slotOf(name, type));
            }
            case IRValue.Named named -> emitNamed(named);
            case IRValue.Values(List<IRValue> values) -> values.forEach(this::emitValue);
            case IRValue.FunctionRef(String name, IRType.Function fnType, IRType targetInterface,
                                     String samMethodName, String samDesc, String instDesc,
                                     List<String> capturedNames) ->
                    emitFunctionRef(name, fnType, targetInterface, capturedNames);
            case null, default -> throw new IllegalStateException(
                    "Unexpected IRValue: " + (value != null ? value.getClass().getSimpleName() : "null"));
        }
    }

    private void emitConstInt(final long constIntValue,
                              final IRType.Int type) {
        if (type.bits() == 64) {
            if (constIntValue == 0) {
                code.lconst_0();
            } else if (constIntValue == 1) {
                code.lconst_1();
            } else {
                code.loadConstant(constIntValue);
            }
        } else if (type.bits() == 32) {
            final var intValue = Long.valueOf(constIntValue).intValue();
            switch (intValue) {
                case -1 -> code.iconst_m1();
                case 0 -> code.iconst_0();
                case 1 -> code.iconst_1();
                case 2 -> code.iconst_2();
                case 3 -> code.iconst_3();
                case 4 -> code.iconst_4();
                case 5 -> code.iconst_5();
                default -> {
                    if (intValue >= Byte.MIN_VALUE && intValue <= Byte.MAX_VALUE) {
                        code.bipush(intValue);
                    } else {
                        code.sipush(intValue);
                    }
                }
            }
        } else {
            code.loadConstant(constIntValue);
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
            code.aconst_null();
        } else if (type instanceof IRType.Int intType && intType.bits() == 64) {
            code.lconst_0();
        } else if (type instanceof IRType.Float floatType && floatType.bits() == 64) {
            code.dconst_0();
        } else if (type instanceof IRType.Float) {
            code.fconst_0();
        } else {
            code.iconst_0();
        }
    }

    private void emitNamed(final IRValue.Named named) {
        final var global = findGlobal(named.name());

        if (isStringLiteral(named, global)) {
            emitValue(global.initializer());
            return;
        }

        if (named.isStatic() || (global != null && global.isStatic())) {
            code.getstatic(
                    ClassDesc.ofInternalName(resolveOwnerName(named, global)),
                    resolveFieldName(named, global),
                    ClassDesc.ofDescriptor(BytecodeHelper.createDescriptor(named.type()))
            );
        } else {
            code.getfield(
                    ClassDesc.ofInternalName(BytecodeHelper.toInternalName(named.ownerType())),
                    named.name(),
                    ClassDesc.ofDescriptor(BytecodeHelper.createDescriptor(named.type()))
            );
        }
    }

    private void emitFunctionRef(final String name,
                                 final IRType.Function fnType,
                                 final IRType targetInterface,
                                 final List<String> capturedNames) {
        final var owner = name.contains(".")
                ? name.substring(0, name.lastIndexOf('.')).replace('.', '/')
                : "java/lang/invoke/MethodHandles";
        final var methodName = name.contains(".")
                ? name.substring(name.lastIndexOf('.') + 1)
                : name;
        final var descriptor = BytecodeHelper.createDescriptor(fnType.paramTypes(), fnType.returnType());
        final var handle = methodHandleDesc(
                DirectMethodHandleDesc.Kind.STATIC,
                owner,
                methodName,
                descriptor
        );

        if (targetInterface instanceof IRType.Ptr ptr && ptr.jvmDescriptor() != null) {
            emitSamConversion(handle, fnType, ptr.jvmDescriptor(), capturedNames);
        } else {
            code.loadConstant(handle);
        }
    }

    private void emitSamConversion(final MethodHandleDesc implHandle,
                                   final IRType.Function fnType,
                                   final String interfaceDescriptor,
                                   final List<String> capturedNames) {
        final var functionalInterface = toClassDesc(interfaceDescriptor);
        final var samMethod = findSamMethod(functionalInterface);
        if (samMethod == null) {
            code.loadConstant(implHandle);
            return;
        }

        final var samMethodName = samMethod.name();
        final var erasedSamDescriptor = samMethod.methodType().descriptorString();

        final var samParamCount = samMethod.methodType().parameterCount();
        final var capturedVarCount = fnType.paramTypes().size() - samParamCount;

        final var samMethodType = samMethod.methodType();
        final var instantiatedDescriptor = BytecodeHelper.createDescriptor(
                fnType.paramTypes().subList(capturedVarCount, fnType.paramTypes().size()),
                fnType.returnType()
        );
        final var instantiatedMethodType = MethodTypeDesc.ofDescriptor(instantiatedDescriptor);

        final var bsmHandle = methodHandleDesc(
                DirectMethodHandleDesc.Kind.STATIC,
                "java/lang/invoke/LambdaMetafactory",
                "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;"
        );

        final var capturedVarTypes = fnType.paramTypes().subList(0, capturedVarCount);

        for (int i = 0; i < capturedVarCount; i++) {
            final String capturedName;
            if (capturedNames != null && i < capturedNames.size()) {
                capturedName = capturedNames.get(i);
            } else {
                capturedName = "%" + i;
            }
            load(loadStoreKind(capturedVarTypes.get(i)),
                    slots.slotOf(capturedName, capturedVarTypes.get(i)));
        }

        code.invokedynamic(
                DynamicCallSiteDesc.of(
                        bsmHandle,
                        samMethodName,
                        MethodTypeDesc.of(
                                functionalInterface,
                                capturedVarTypes.stream()
                                        .map(t -> toClassDesc(BytecodeHelper.createDescriptor(t)))
                                        .toArray(ClassDesc[]::new)
                        ),
                        samMethodType,
                        implHandle,
                        instantiatedMethodType
                )
        );
    }

    private record SamMethodInfo(String name, MethodTypeDesc methodType) {}

    private SamMethodInfo findSamMethod(final ClassDesc functionalInterface) {
        try {
            final var clazz = Class.forName(
                    functionalInterface.displayName(),
                    false,
                    Thread.currentThread().getContextClassLoader());
            for (final var m : clazz.getMethods()) {
                if (m.isDefault() || java.lang.reflect.Modifier.isStatic(m.getModifiers()) || m.getDeclaringClass() == Object.class) {
                    continue;
                }
                if (java.lang.reflect.Modifier.isAbstract(m.getModifiers())) {
                    final var paramDescs = java.util.Arrays.stream(m.getParameterTypes())
                            .map(Class::descriptorString)
                            .map(ClassDesc::ofDescriptor)
                            .toArray(ClassDesc[]::new);
                    return new SamMethodInfo(
                            m.getName(),
                            MethodTypeDesc.of(
                                    ClassDesc.ofDescriptor(m.getReturnType().descriptorString()),
                                    paramDescs)
                    );
                }
            }
        } catch (final ClassNotFoundException | NoClassDefFoundError e) {
            // Fallback: geen SAM-methode gevonden
        }
        return null;
    }

    private static DirectMethodHandleDesc methodHandleDesc(final DirectMethodHandleDesc.Kind kind,
                                                           final String ownerInternalName,
                                                           final String methodName,
                                                           final String methodDescriptor) {
        return MethodHandleDesc.ofMethod(
                kind,
                ClassDesc.ofInternalName(ownerInternalName),
                methodName,
                MethodTypeDesc.ofDescriptor(methodDescriptor)
        );
    }

    // -------------------------------------------------------
    // Global-opzoekingen en namen
    // -------------------------------------------------------

    private IRGlobal findGlobal(final String name) {
        return codeEmitter.findGlobal(name);
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
        if (result instanceof IRValue.Temp temp) {
            store(loadStoreKind(temp.type()),
                    slots.slotOf(temp.name(), temp.type()));
        }
    }

    /**
     * Generieke typed load: de CodeBuilder API kent alleen per-type
     * varianten (iload/lload/fload/dload/aload), dus hier een dispatcher.
     */
    private void load(final java.lang.classfile.TypeKind kind, final int slot) {
        switch (kind) {
            case INT -> code.iload(slot);
            case LONG -> code.lload(slot);
            case FLOAT -> code.fload(slot);
            case DOUBLE -> code.dload(slot);
            case REFERENCE -> code.aload(slot);
            default -> throw new UnsupportedOperationException("Unsupported load kind: " + kind);
        }
    }

    private void store(final java.lang.classfile.TypeKind kind, final int slot) {
        switch (kind) {
            case INT -> code.istore(slot);
            case LONG -> code.lstore(slot);
            case FLOAT -> code.fstore(slot);
            case DOUBLE -> code.dstore(slot);
            case REFERENCE -> code.astore(slot);
            default -> throw new UnsupportedOperationException("Unsupported store kind: " + kind);
        }
    }

    /**
     * Bepaalt de TypeKind voor load/store-instructies op basis van het IRType.
     */
    private static java.lang.classfile.TypeKind loadStoreKind(final IRType type) {
        return switch (type) {
            case IRType.Int intType -> intType.bits() == 64
                    ? java.lang.classfile.TypeKind.LONG
                    : java.lang.classfile.TypeKind.INT;
            case IRType.Float floatType -> floatType.bits() == 64
                    ? java.lang.classfile.TypeKind.DOUBLE
                    : java.lang.classfile.TypeKind.FLOAT;
            case IRType.Bool ignored -> java.lang.classfile.TypeKind.INT;
            default -> java.lang.classfile.TypeKind.REFERENCE;
        };
    }

    // -------------------------------------------------------
    // Opcode-resolutie
    // -------------------------------------------------------

    /**
     * Emitteert een stack-operand-instructie. De CodeBuilder API kent geen
     * generieke op(Opcode)-methode, daarom vertaalt deze dispatcher elke
     * gebruikte opcode naar de bijbehorende getypeerde methode.
     */
    private void emitOpcode(final Opcode opcode) {
        switch (opcode) {
            case IADD -> code.iadd();
            case LADD -> code.ladd();
            case FADD -> code.fadd();
            case DADD -> code.dadd();
            case ISUB -> code.isub();
            case LSUB -> code.lsub();
            case FSUB -> code.fsub();
            case DSUB -> code.dsub();
            case IMUL -> code.imul();
            case LMUL -> code.lmul();
            case FMUL -> code.fmul();
            case DMUL -> code.dmul();
            case IDIV -> code.idiv();
            case LDIV -> code.ldiv();
            case FDIV -> code.fdiv();
            case DDIV -> code.ddiv();
            case IREM -> code.irem();
            case LREM -> code.lrem();
            case FREM -> code.frem();
            case DREM -> code.drem();
            case IAND -> code.iand();
            case LAND -> code.land();
            case IOR -> code.ior();
            case LOR -> code.lor();
            case IXOR -> code.ixor();
            case LXOR -> code.lxor();
            case LCMP -> code.lcmp();
            case FCMPL -> code.fcmpl();
            case FCMPG -> code.fcmpg();
            case DCMPL -> code.dcmpl();
            case DCMPG -> code.dcmpg();
            case BALOAD -> code.baload();
            case SALOAD -> code.saload();
            case IALOAD -> code.iaload();
            case LALOAD -> code.laload();
            case FALOAD -> code.faload();
            case DALOAD -> code.daload();
            case AALOAD -> code.aaload();
            default -> throw new UnsupportedOperationException(
                    "Geen ondersteuning voor opcode: " + opcode);
        }
    }

    private Opcode resolveLoadOpcode(final IRType type) {
        return switch (type) {
            case IRType.Int intType -> intType.bits() == 64 ? Opcode.LLOAD : Opcode.ILOAD;
            case IRType.Float floatType -> floatType.bits() == 64 ? Opcode.DLOAD : Opcode.FLOAD;
            case IRType.Ptr ignored -> Opcode.ALOAD;
            case IRType.Bool ignored -> Opcode.ILOAD;
            case IRType.Array ignored -> Opcode.ALOAD;
            case IRType.Void ignored -> Opcode.NOP;
            default -> throw new UnsupportedOperationException("Unsupported load type: " + type);
        };
    }

    private Opcode resolveStoreOpcode(final IRType type) {
        return switch (type) {
            case IRType.Int intType -> intType.bits() == 64 ? Opcode.LSTORE : Opcode.ISTORE;
            case IRType.Float floatType -> floatType.bits() == 64 ? Opcode.DSTORE : Opcode.FSTORE;
            case IRType.Ptr ignored -> Opcode.ASTORE;
            case IRType.Bool ignored -> Opcode.ISTORE;
            case IRType.Array ignored -> Opcode.ASTORE;
            case IRType.Void ignored -> Opcode.NOP;
            default -> throw new UnsupportedOperationException("Unsupported store type: " + type);
        };
    }

    private Opcode resolveArrayLoadOpcode(final IRType elemType) {
        return switch (elemType) {
            case IRType.Int t -> switch (t.bits()) {
                case 8 -> Opcode.BALOAD;
                case 16 -> Opcode.SALOAD;
                case 32 -> Opcode.IALOAD;
                case 64 -> Opcode.LALOAD;
                default -> Opcode.IALOAD;
            };
            case IRType.Float t -> t.bits() == 32 ? Opcode.FALOAD : Opcode.DALOAD;
            case IRType.Bool ignored -> Opcode.BALOAD;
            default -> Opcode.AALOAD;
        };
    }

    private Opcode resolveAddOpcode(final IRValue left) {
        return resolveAddOpcode(left.type());
    }

    private Opcode resolveAddOpcode(final IRType type) {
        return switch (type) {
            case IRType.Int intType -> intType.bits() == 64 ? Opcode.LADD : Opcode.IADD;
            case IRType.Float floatType -> floatType.bits() == 32 ? Opcode.FADD : Opcode.DADD;
            case IRType.Ptr ptr -> resolveAddOpcode(ptr.pointee());
            default -> throw new UnsupportedOperationException("Unsupported add type: " + type);
        };
    }

    private Opcode resolveSubOpcode(final IRValue left) {
        return resolveSubOpcode(left.type());
    }

    private Opcode resolveSubOpcode(final IRType type) {
        return switch (type) {
            case IRType.Int intType -> intType.bits() == 64 ? Opcode.LSUB : Opcode.ISUB;
            case IRType.Float floatType -> floatType.bits() == 32 ? Opcode.FSUB : Opcode.DSUB;
            case IRType.Ptr ptr -> resolveSubOpcode(ptr.pointee());
            default -> throw new UnsupportedOperationException("Unsupported sub type: " + type);
        };
    }

    private Opcode resolveMulOpcode(final IRValue left) {
        return switch (left.type()) {
            case IRType.Int intType -> intType.bits() == 64 ? Opcode.LMUL : Opcode.IMUL;
            case IRType.Float floatType -> floatType.bits() == 32 ? Opcode.FMUL : Opcode.DMUL;
            default -> throw new UnsupportedOperationException("Unsupported mul type: " + left.type());
        };
    }

    private Opcode resolveDivOpcode(final IRValue left) {
        return switch (left.type()) {
            case IRType.Int intType -> intType.bits() == 64 ? Opcode.LDIV : Opcode.IDIV;
            case IRType.Float floatType -> floatType.bits() == 32 ? Opcode.FDIV : Opcode.DDIV;
            default -> throw new UnsupportedOperationException("Unsupported div type: " + left.type());
        };
    }

    private Opcode resolveModOpcode(final IRValue left) {
        return switch (left.type()) {
            case IRType.Int intType -> intType.bits() == 64 ? Opcode.LREM : Opcode.IREM;
            case IRType.Float floatType -> floatType.bits() == 32 ? Opcode.FREM : Opcode.DREM;
            default -> throw new UnsupportedOperationException("Unsupported mod type: " + left.type());
        };
    }

    private Opcode resolveAndOpcode(final IRValue left) {
        return switch (left.type()) {
            case IRType.Int intType -> intType.bits() == 64 ? Opcode.LAND : Opcode.IAND;
            default -> throw new UnsupportedOperationException("Unsupported and type: " + left.type());
        };
    }

    private Opcode resolveOrOpcode(final IRValue left) {
        return switch (left.type()) {
            case IRType.Int intType -> intType.bits() == 64 ? Opcode.LOR : Opcode.IOR;
            default -> throw new UnsupportedOperationException("Unsupported or type: " + left.type());
        };
    }

    private Opcode resolveXorOpcode(final IRValue left) {
        return switch (left.type()) {
            case IRType.Int intType -> intType.bits() == 64 ? Opcode.LXOR : Opcode.IXOR;
            default -> throw new UnsupportedOperationException("Unsupported xor type: " + left.type());
        };
    }

    private Opcode resolveBitAndOpcode(final IRValue left) {
        return resolveAndOpcode(left);
    }

    private Opcode resolveBitOrOpcode(final IRValue left) {
        return resolveOrOpcode(left);
    }

    private Opcode resolveBitXorOpcode(final IRValue left) {
        return resolveXorOpcode(left);
    }

    // -------------------------------------------------------
    // Lokale variabelen (debuginfo)
    // -------------------------------------------------------

    void visitLocalVariables(final IRFunction function,
                             final String startLabelName) {
        final var start = getOrCreateLabel(startLabelName);
        final var end = getOrCreateLabel("END");
        final var allocaVersions = function.allocaVersions();

        for (final var param : function.params) {
            if (param instanceof IRValue.Temp temp && slots.hasSlot(temp.name())) {
                final var paramName = SlotAllocator.normalize(temp.name());
                final var index = slots.getSlot(temp.name());
                final var paramDescriptor = BytecodeHelper.createDescriptor(temp.type());

                code.localVariable(
                        index,
                        paramName,
                        ClassDesc.ofDescriptor(paramDescriptor),
                        start,
                        end
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

            code.localVariable(
                    index,
                    localVar.sourceName(),
                    ClassDesc.ofDescriptor(descriptor),
                    start,
                    end
            );
        }
    }
}

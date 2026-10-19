package io.github.potjerodekool.nabu.compiler.backend.bytecode;

import io.github.potjerodekool.nabu.backend.ir.CallKind;
import io.github.potjerodekool.nabu.backend.ir.IRBasicBlock;
import io.github.potjerodekool.nabu.backend.ir.IRBuilder;
import io.github.potjerodekool.nabu.backend.ir.IRFunction;
import io.github.potjerodekool.nabu.backend.ir.IRModule;
import io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;
import io.github.potjerodekool.nabu.debug.SourceLocation;
import io.github.potjerodekool.nabu.lang.Flags;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;

import java.util.*;

/**
 * Vertaalt het bytecode-lichaam ({@code Code}-attribuut) van een ASM
 * {@link MethodNode} direct naar Nabu's eigen {@code IRFunction}/
 * {@code IRBasicBlock}/{@code IRInstruction}-vorm, zonder tussenstap via
 * Nabu's source-AST.
 *
 * <p>Dit is het bytecode-equivalent van {@code IrGeneratingVisitor} (die
 * vanuit Nabu's getypeerde source-AST dezelfde IR-vorm vult). Beide vullen
 * dezelfde {@link IRBuilder}, zodat de rest van de pijplijn (SSA-opbouw,
 * optimalisatie, native codegen) ongewijzigd blijft.
 *
 * <p><b>Status (fase 1, zie BYTECODE_COMPILATION_PLAN.md):</b> dekt de
 * opcode-categorieen 1-4 (constanten/stack, locals, arrays, rekenkundig),
 * categorie 5 zonder exception-tabellen/switches, categorie 6
 * (velden/objecten) en categorie 7 (methode-aanroepen, GEEN
 * {@code invokedynamic}). Expliciet nog niet geimplementeerd: exception-
 * tabellen (try/catch), {@code tableswitch}/{@code lookupswitch},
 * {@code invokedynamic}, {@code multianewarray}. Deze gooien een duidelijke
 * {@link UnsupportedOperationException} zodat een build die ze raakt precies
 * aangeeft welk stuk nog moet worden gebouwd (zie taken #3/#4 in de
 * sessie-taaklijst).
 *
 * <p>Functienaamconventie: identiek aan {@code IrGeneratingVisitor}
 * ({@code eigenaarQualifiedName + "_" + methodName}, {@code "_init"} voor
 * constructors, {@code "_clinit"} voor static initializers) - GEEN
 * descriptor-suffix. Zie de "Aanvullende bevinding"-sectie in het plan-
 * document voor het overload-naamsbotsingsrisico dat dit met zich meebrengt.
 */
public final class BytecodeMethodLowering {

    private final IRBuilder builder;
    private final String ownerQualifiedName;
    private final ClassNode classNode;
    private final MethodNode methodNode;

    /** JVM-local-slot-index -> alloca-pointer (Temp met Ptr-type). */
    private final Map<Integer, IRValue> localSlots = new HashMap<>();

    /** ASM LabelNode -> vooraf aangemaakt IRBasicBlock (voor voorwaartse sprongen). */
    private final Map<Label, IRBasicBlock> blocksByAsmLabel = new IdentityHashMap<>();

    /** Labels die een exception-handler-ingang zijn (zie preCreateJumpTargetBlocks). */
    private final Set<Label> handlerLabels = new HashSet<>();
    /**
     * Welke IRBasicBlock-objecten al aan de functie zijn toegevoegd
     * (builder.currentFunction().addBlock()). Een handlerblok heeft door
     * emitTryCatchRegions() AL een TryCatchRegion-marker-instructie staan
     * voordat switchToBlock() het ooit bereikt - dus "instructions().isEmpty()"
     * is GEEN betrouwbare "is dit al toegevoegd?"-check voor zo'n blok
     * (addBlock() zou dan nooit aangeroepen worden, en het blok zou
     * stilletjes uit de uiteindelijke codegen vallen). Deze set is de
     * correcte, aparte boekhouding daarvoor.
     */
    private final Set<IRBasicBlock> addedBlocks = new HashSet<>();

    /** Handler-label -> interne naam van het (eerste) catch-type, voor het type van de %exn.-temp. */
    private final Map<Label, String> handlerExceptionInternalName = new HashMap<>();

    /** Simulatie van de operand-stack tijdens de lineaire emissiepas. */
    private final Deque<IRValue> stack = new ArrayDeque<>();

    private Frame<BasicValue>[] frames;

    private final Set<String> referencedBinaryNames;

    /**
     * Methode-niveau reachability-taken (zie {@link MethodRef}) - gevuld
     * door elke aanroepende instructie/hulpmethode die weet welke methode
     * ze aanroept, zodat {@code BytecodeReachabilityCompiler} alleen
     * daadwerkelijk aangeroepen methodes hoeft te loweren i.p.v. elke
     * methode van elke bereikte klasse (dat laatste deed de sluiting
     * vanuit java.util.regex.Pattern/Matcher tot ~5000 klassen opblazen,
     * inclusief ongebruikte takken zoals Serializable's readObject/
     * writeObject die weer java.lang.invoke.MethodHandle.invokeExact
     * binnenhalen - een aanroepmechanisme dat niet via gewone name-mangling
     * op te lossen is).
     */
    private final Set<MethodRef> referencedMethods;

    /**
     * Synthetische klassen die tijdens het lowerern van deze methode zijn
     * aangemaakt (zie {@code emitLambdaMetafactoryIndy}) - door
     * {@code BytecodeReachabilityCompiler} na {@link #lower()} opgehaald en
     * aan de resultaatlijst toegevoegd, net als een normaal geladen klasse.
     */
    private final List<IRModule> extraModules = new ArrayList<>();

    public BytecodeMethodLowering(final IRBuilder builder,
                                  final String ownerQualifiedName,
                                  final ClassNode classNode,
                                  final MethodNode methodNode,
                                  final Set<String> referencedBinaryNames,
                                  final Set<MethodRef> referencedMethods) {
        this.builder = builder;
        this.ownerQualifiedName = ownerQualifiedName;
        this.classNode = classNode;
        this.methodNode = methodNode;
        this.referencedBinaryNames = referencedBinaryNames;
        this.referencedMethods = referencedMethods;
    }

    private void noteReference(final String internalName) {
        if (internalName != null && !internalName.startsWith("[")) {
            referencedBinaryNames.add(internalName);
        }
    }

    private void noteMethodReference(final String ownerInternalName,
                                      final String name,
                                      final String desc,
                                      final boolean virtual) {
        if (ownerInternalName != null && !ownerInternalName.startsWith("[")) {
            referencedMethods.add(new MethodRef(ownerInternalName, name, desc, virtual));
        }
    }

    /** Zie veld-javadoc {@link #extraModules}. */
    public List<IRModule> extraModules() {
        return extraModules;
    }

    /**
     * Lowert deze methode en voegt de resulterende {@link IRFunction} toe
     * aan de module die {@code builder} opbouwt.
     *
     * @return de nieuwe functie, of {@code null} als de methode geen body
     *         heeft (abstract/native) - dan wordt alleen een externe
     *         declaratie geregistreerd.
     */
    public IRFunction lower() {
        final boolean isStatic = Flags.hasFlag(methodNode.access, Opcodes.ACC_STATIC);
        final boolean isConstructor = "<init>".equals(methodNode.name);
        final boolean isStaticInit = "<clinit>".equals(methodNode.name);
        final boolean hasBody = methodNode.instructions != null
                && methodNode.instructions.size() > 0;

        final Type methodType = Type.getMethodType(methodNode.desc);
        final IRType returnType = DescriptorMapper.mapType(methodType.getReturnType());

        final List<IRValue> params = new ArrayList<>();
        final Type[] argTypes = methodType.getArgumentTypes();

        if (!isStatic && !isStaticInit) {
            params.add(new IRValue.Temp("%this",
                    new IRType.Ptr(IRType.I8, "L" + classNode.name + ";")));
        }

        // JVM-local-slotnummers: 'this' (indien aanwezig) neemt slot 0 in;
        // long/double nemen 2 slots in.
        int slot = isStatic || isStaticInit ? 0 : 1;
        final int[] paramSlots = new int[argTypes.length];
        for (int i = 0; i < argTypes.length; i++) {
            final Type argType = argTypes[i];
            final IRType irParamType = DescriptorMapper.mapType(argType);
            params.add(new IRValue.Temp("%p" + i, irParamType));
            paramSlots[i] = slot;
            slot += argType.getSize();
        }

        final long flags = AccessFlags.toNabuFlags(methodNode.access);

        String fnName = ownerQualifiedName + "_" + methodNode.name;
        if (isConstructor) {
            fnName = ownerQualifiedName + "_init";
        } else if (isStaticInit) {
            fnName = ownerQualifiedName + "_clinit";
        }

        if (!hasBody && Flags.hasFlag(methodNode.access, Opcodes.ACC_ABSTRACT)) {
            // Abstract: als gewone (body-loze) functie met ABSTRACT-vlag, net
            // als de source-frontend. Een externe declaratie zou buiten de
            // vtable/itable vallen, waardoor elke aanroep een directe call
            // naar een nergens gedefinieerd symbool wordt.
            builder.beginFunction(fnName, returnType, params, flags, false);
            builder.endFunction();
            return null;
        }
        if (!hasBody && Flags.hasFlag(methodNode.access, Opcodes.ACC_NATIVE)
                && !isStatic
                && (methodNode.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL)) == 0) {
            // Een virtuele native (Object.hashCode, clone): als body-loze
            // NATIVE-functie in het module, zodat ze een vtable-slot krijgt dat
            // overrides (String.hashCode) delen. Alleen extern gedeclareerd viel
            // ze buiten de vtable en werd elke `key.hashCode()` via Object een
            // directe call naar de runtime — nooit naar de override.
            builder.beginFunction(fnName, returnType, params, flags, false);
            builder.endFunction();
            return null;
        }
        if (!hasBody) {
            // Abstract of native: alleen extern declareren, net als
            // IrGeneratingVisitor doet voor Java-`native`-methoden.
            builder.declareExternalFunction(fnName,
                    new IRType.Function(returnType,
                            params.stream().map(IRValue::type).toList()));
            return null;
        }

        final IRFunction fn = builder.beginFunction(fnName, returnType, params, flags, isConstructor);
        builder.setLocation(ownerQualifiedName + ".class", -1, -1);

        // 'this' en parameters krijgen een alloca-slot, net als lokale
        // variabelen, zodat ALOAD/ASTORE/ILOAD/... op slotnummer uniform
        // via emitLoad/emitStore lopen.
        int paramIdx = 0;
        if (!isStatic && !isStaticInit) {
            final IRValue thisParam = params.get(0);
            final IRValue thisAlloca = builder.emitAlloca("this", thisParam.type());
            builder.emitStore(thisAlloca, thisParam);
            localSlots.put(slotKey(0, KIND_REF), thisAlloca);
            paramIdx = 1;
        }
        for (int i = 0; i < argTypes.length; i++) {
            final IRValue p = params.get(paramIdx + i);
            final IRValue alloca = builder.emitAlloca("p" + i, p.type());
            builder.emitStore(alloca, p);
            localSlots.put(slotKey(paramSlots[i], kindOf(argTypes[i])), alloca);
        }

        runAnalyzer();
        preCreateJumpTargetBlocks();
        emitTryCatchRegions();
        emitInstructions();

        builder.endFunction();
        return fn;
    }

    // -------------------------------------------------------
    // ASM-analyzer: stack/locals-typering per instructie (handelt
    // block-merges bij loops/sprongen zelf af, zodat wij dat niet met de
    // hand hoeven te simuleren).
    // -------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void runAnalyzer() {
        try {
            final var analyzer = new Analyzer<BasicValue>(new BasicInterpreter());
            frames = analyzer.analyze(classNode.name, methodNode);
        } catch (final AnalyzerException e) {
            throw new IllegalStateException(
                    "Cannot analyze stack/locals for " + ownerQualifiedName
                            + "." + methodNode.name + methodNode.desc, e);
        }
    }

    private void preCreateJumpTargetBlocks() {
        for (final var insn : methodNode.instructions) {
            if (insn instanceof LabelNode labelNode) {
                blocksByAsmLabel.computeIfAbsent(labelNode.getLabel(),
                        l -> new IRBasicBlock(uniqueBlockName()));
            } else if (insn instanceof JumpInsnNode jump) {
                blocksByAsmLabel.computeIfAbsent(jump.label.getLabel(),
                        l -> new IRBasicBlock(uniqueBlockName()));
            } else if (insn instanceof TableSwitchInsnNode ts) {
                blocksByAsmLabel.computeIfAbsent(ts.dflt.getLabel(),
                        l -> new IRBasicBlock(uniqueBlockName()));
                for (final var l : ts.labels) {
                    blocksByAsmLabel.computeIfAbsent(l.getLabel(),
                            ll -> new IRBasicBlock(uniqueBlockName()));
                }
            } else if (insn instanceof LookupSwitchInsnNode ls) {
                blocksByAsmLabel.computeIfAbsent(ls.dflt.getLabel(),
                        l -> new IRBasicBlock(uniqueBlockName()));
                for (final var l : ls.labels) {
                    blocksByAsmLabel.computeIfAbsent(l.getLabel(),
                            ll -> new IRBasicBlock(uniqueBlockName()));
                }
            }
        }
        // Exception-handlers zijn ook sprongdoelen (landingpads). try-start/
        // try-end zijn GEEN sprongdoelen in de klassieke zin, maar MOETEN
        // wel blokgrenzen worden: TryCatchRegion verwijst naar ze via
        // bloklabel (net als de source-route doet), dus ook deze labels
        // registreren als splitsingspunt.
        for (final var tcb : methodNode.tryCatchBlocks) {
            final var handlerLabel = tcb.handler.getLabel();
            blocksByAsmLabel.computeIfAbsent(handlerLabel,
                    l -> new IRBasicBlock(uniqueBlockName()));
            handlerLabels.add(handlerLabel);
            handlerExceptionInternalName.putIfAbsent(handlerLabel, tcb.type);

            blocksByAsmLabel.computeIfAbsent(tcb.start.getLabel(),
                    l -> new IRBasicBlock(uniqueBlockName()));
            blocksByAsmLabel.computeIfAbsent(tcb.end.getLabel(),
                    l -> new IRBasicBlock(uniqueBlockName()));
        }
    }

    /**
     * Voegt een {@code TryCatchRegion}-instructie toe aan het begin van elk
     * handlerblok, analoog aan {@code IrGeneratingVisitor.visitTryStatement}
     * - zodat de native backend exact dezelfde metadata-vorm gebruikt voor
     * bytecode- en source-gelowerde exceptionhandling.
     */
    private void emitTryCatchRegions() {
        for (final var tcb : methodNode.tryCatchBlocks) {
            final IRBasicBlock startBlk = blocksByAsmLabel.get(tcb.start.getLabel());
            final IRBasicBlock endBlk = blocksByAsmLabel.get(tcb.end.getLabel());
            final IRBasicBlock handlerBlk = blocksByAsmLabel.get(tcb.handler.getLabel());
            final String exceptionType = tcb.type != null ? tcb.type.replace('/', '.') : null;

            handlerBlk.add(0, new IRInstruction.TryCatchRegion(
                    startBlk.label(),
                    endBlk.label(),
                    handlerBlk.label(),
                    exceptionType,
                    builder.currentLocation()
            ));
        }
    }

    private int blockCounter = 0;
    private String uniqueBlockName() {
        return "bc" + (blockCounter++);
    }

    // -------------------------------------------------------
    // Lineaire emissiepas
    // -------------------------------------------------------

    private void emitInstructions() {
        final AbstractInsnNode[] insns = methodNode.instructions.toArray();

        for (int i = 0; i < insns.length; i++) {
            final AbstractInsnNode insn = insns[i];

            if (insn instanceof LabelNode labelNode) {
                switchToBlock(labelNode, frames[i]);
                continue;
            }
            if (insn instanceof LineNumberNode || insn instanceof FrameNode) {
                continue;
            }

            emitOne(insn, frames[i]);
        }
    }

    private void switchToBlock(final LabelNode labelNode, final Frame<BasicValue> frameAtLabel) {
        final Label asmLabel = labelNode.getLabel();
        final IRBasicBlock target = blocksByAsmLabel.get(asmLabel);
        if (target == null) {
            return; // label zonder sprong-/handlerverwijzing; geen blokwissel nodig
        }
        if (builder.currentBlock() != target) {
            final boolean isHandlerEntry = handlerLabels.contains(asmLabel);
            final boolean fallsThrough = !isHandlerEntry && !builder.currentBlockTerminated();

            boolean mergedOnFallThrough = false;
            if (fallsThrough) {
                // Normale (niet-exceptionele) doorval naar het volgende blok.
                // Wat nog op de operand-stack staat, gaat via de merge-slots
                // mee (zie storeStackForEdge).
                mergedOnFallThrough = storeStackForEdge(labelNode);
                builder.emitBranch(target);
            }
            // Een handlerblok wordt NOOIT via gewone controlestroom bereikt
            // (alleen via exception-dispatch) - in correcte, door javac
            // gegenereerde bytecode eindigt het voorgaande blok hier al met
            // een expliciete GOTO om de handler over te slaan, dus
            // currentBlockTerminated() is dan al waar. Geen kunstmatige
            // branch toevoegen voor dat (dode) pad.

            if (addedBlocks.add(target)) {
                builder.currentFunction().addBlock(target);
            }
            builder.setCurrentBlock(target);

            if (isHandlerEntry) {
                // JVM-spec: bij binnenkomst van een exception-handler bevat
                // de operand-stack EXACT 1 waarde (het exception-object).
                // Onze eigen `stack`-simulatie bevat op dit punt nog de
                // (ongeldige) restanten van het verlaten blok - resetten.
                stack.clear();
                final String exInternalName = handlerExceptionInternalName.get(asmLabel);
                final String exDescriptor = exInternalName != null
                        ? "L" + exInternalName + ";"
                        : "Ljava/lang/Throwable;";
                stack.push(new IRValue.Temp("%exn." + target.label(),
                        new IRType.Ptr(IRType.I8, exDescriptor)));
            } else if (!fallsThrough) {
                // Dit label wordt NIET via gewone doorval bereikt - het
                // vorige blok eindigde al met een expliciete sprong/
                // return/throw (bv. de GOTO aan het einde van de "then"-tak
                // van `boolean b = x > 0 ? ... : ...;`-achtige boolean-
                // materialisatie). Onze `stack`-simulatie draagt op dit
                // moment nog de restanten van dat VERLATEN pad (bv. de
                // ICONST_1 vóór die GOTO) - die horen hier niet, want dit
                // label wordt ALLEEN bereikt via de sprong vanaf een ANDERE
                // (niet-tekstueel-aangrenzende) plek. Resetten naar wat de
                // bytecode-verifier hier daadwerkelijk garandeert, net als
                // hierboven voor exception-handlers, maar generiek voor elk
                // sprongdoel.
                resyncStackFromFrame(target, frameAtLabel);
            } else if (mergedOnFallThrough) {
                // Ook het doorval-pad leest uit de slots: een ANDERE
                // voorganger (de sprong van de andere ternary-tak) kan er een
                // andere waarde in hebben gezet.
                resyncStackFromFrame(target, frameAtLabel);
            }
        }
    }

    /**
     * Operand-stack-merge: een sprongdoel kan met een niet-lege operand-stack
     * bereikt worden (bv. {@code x != null ? x : f()} - beide takken laten
     * hun waarde op de stack achter). Elke voorganger schrijft zijn
     * stackwaarden vóór de sprong in een gedeeld slot per stackpositie; het
     * doelblok leest ze daar weer uit ({@link #resyncStackFromFrame}).
     *
     * <p>De slots volgen de {@code %phi}-conventie van PhiElimination, zodat
     * de backends ze als gewone stackslots behandelen. Het slot-type komt uit
     * het frame van het DOEL (voor elke voorganger hetzelfde), niet uit de
     * waarde van de toevallige voorganger.
     *
     * @return of de stack daadwerkelijk in de slots gezet is
     */
    private boolean storeStackForEdge(final LabelNode targetLabel) {
        final IRBasicBlock target = blocksByAsmLabel.get(targetLabel.getLabel());
        if (target == null || stack.isEmpty()
                || handlerLabels.contains(targetLabel.getLabel())) {
            return false;
        }
        final int index = methodNode.instructions.indexOf(targetLabel);
        final Frame<BasicValue> frame = index >= 0 && index < frames.length ? frames[index] : null;
        if (frame == null || frame.getStackSize() != stack.size()) {
            // Simulatie en verifier-frame lopen uiteen (dode code, of een
            // verlaten pad): niets schrijven, het oude gedrag blijft gelden.
            return false;
        }
        // `stack` itereert van top naar bodem; frame-index 0 is de bodem.
        final List<IRValue> values = new ArrayList<>(stack);
        Collections.reverse(values);
        for (int i = 0; i < values.size(); i++) {
            builder.emitStore(mergeSlot(target, i, frame.getStack(i)), values.get(i));
        }
        return true;
    }

    private IRValue mergeSlot(final IRBasicBlock target, final int stackIndex, final BasicValue bv) {
        return new IRValue.Temp("%phi.merge." + target.label() + "." + stackIndex,
                basicValueToIrType(bv));
    }

    /**
     * Herstelt {@code stack} naar de door ASM's Analyzer geverifieerde
     * operand-stackhoogte op dit sprongdoel, met verse plaatshoudertemps
     * per verwacht element. In de praktijk is deze hoogte bijna altijd 0
     * (de normale situatie bij een sprongdoel) - een eventuele
     * daadwerkelijke waarde op de stack bij een merge-punt (zoals het
     * resultaat van een korte boolean-materialisatie) komt altijd via
     * GEWONE doorval van de "winnende" tak binnen, dus wordt al correct
     * gedragen door het `fallsThrough`-pad hierboven; deze reset voorkomt
     * alleen dat het VERLATEN pad zijn eigen (irrelevante) restanten hier
     * laat lekken.
     */
    private void resyncStackFromFrame(final IRBasicBlock target, final Frame<BasicValue> frameAtLabel) {
        stack.clear();
        if (frameAtLabel == null) {
            return;
        }
        for (int i = 0; i < frameAtLabel.getStackSize(); i++) {
            final IRValue slot = mergeSlot(target, i, frameAtLabel.getStack(i));
            stack.push(builder.emitLoad(slot.type(), slot));
        }
    }

    private IRType basicValueToIrType(final BasicValue bv) {
        if (bv == null) {
            return new IRType.Ptr(IRType.I8);
        }
        final Type t = bv.getType();
        if (t == null) {
            return new IRType.Ptr(IRType.I8);
        }
        try {
            return DescriptorMapper.mapType(t);
        } catch (final RuntimeException e) {
            return new IRType.Ptr(IRType.I8);
        }
    }

    private void emitOne(final AbstractInsnNode insn, final Frame<BasicValue> frameBefore) {
        final int opcode = insn.getOpcode();

        // Diagnostische sanity-check: ASM's eigen Analyzer (runAnalyzer())
        // kent de door de bytecode-verifier gegarandeerde operand-stack-
        // hoogte vóór elke instructie al exact. Onze eigen `stack`-simulatie
        // MOET daarmee overeenkomen - als dat niet zo is, is er elders al
        // een push/pop-fout gemaakt (bv. een nog niet ondersteunde opcode
        // die stilzwijgend de verkeerde stack-balans heeft) en loopt de
        // ECHTE crash (zoals een pop() op een lege stack) pas een of meer
        // instructies LATER, ver van de werkelijke oorzaak. Deze check
        // vangt het exact op het moment dat het misgaat.
        if (frameBefore != null && stack.size() != frameBefore.getStackSize()) {
            throw new IllegalStateException(
                    "Stack-desync in " + ownerQualifiedName + "." + methodNode.name + methodNode.desc
                            + " just before opcode " + opcode + " (" + insn.getClass().getSimpleName()
                            + "): our simulation has " + stack.size()
                            + " value(s) on the stack, ASM's frame analysis expects "
                            + frameBefore.getStackSize() + ".");
        }

        switch (insn.getType()) {
            case AbstractInsnNode.INSN -> emitZeroOperand(opcode);
            case AbstractInsnNode.INT_INSN -> emitIntOperand(opcode, ((IntInsnNode) insn).operand);
            case AbstractInsnNode.VAR_INSN -> emitVarInsn(opcode, ((VarInsnNode) insn).var);
            case AbstractInsnNode.TYPE_INSN -> emitTypeInsn(opcode, ((TypeInsnNode) insn).desc);
            case AbstractInsnNode.FIELD_INSN -> emitFieldInsn(opcode, (FieldInsnNode) insn);
            case AbstractInsnNode.METHOD_INSN -> emitMethodInsn(opcode, (MethodInsnNode) insn);
            case AbstractInsnNode.JUMP_INSN -> emitJumpInsn(opcode, (JumpInsnNode) insn, frameBefore);
            case AbstractInsnNode.LDC_INSN -> emitLdc(((LdcInsnNode) insn).cst);
            case AbstractInsnNode.IINC_INSN -> emitIinc((IincInsnNode) insn);
            case AbstractInsnNode.INVOKE_DYNAMIC_INSN ->
                    emitInvokeDynamicInsn((InvokeDynamicInsnNode) insn);
            case AbstractInsnNode.TABLESWITCH_INSN -> emitTableSwitch((TableSwitchInsnNode) insn);
            case AbstractInsnNode.LOOKUPSWITCH_INSN -> emitLookupSwitch((LookupSwitchInsnNode) insn);
            case AbstractInsnNode.MULTIANEWARRAY_INSN ->
                    emitMultiANewArray((MultiANewArrayInsnNode) insn);
            default -> throw new UnsupportedOperationException(
                    "Unknown instruction type " + insn.getType() + " in "
                            + ownerQualifiedName + "." + methodNode.name);
        }
    }

    // --- Categorie 1: constanten & stack -------------------------------

    private void emitZeroOperand(final int opcode) {
        switch (opcode) {
            case Opcodes.NOP -> { /* niets */ }
            case Opcodes.ACONST_NULL -> stack.push(IRValue.nullPtr(new IRType.Ptr(IRType.I8)));
            case Opcodes.ICONST_M1, Opcodes.ICONST_0, Opcodes.ICONST_1, Opcodes.ICONST_2,
                 Opcodes.ICONST_3, Opcodes.ICONST_4, Opcodes.ICONST_5 ->
                    stack.push(IRValue.ofI32(opcode - Opcodes.ICONST_0));
            case Opcodes.LCONST_0 -> stack.push(IRValue.ofInt(0, 64));
            case Opcodes.LCONST_1 -> stack.push(IRValue.ofInt(1, 64));
            case Opcodes.FCONST_0 -> stack.push(IRValue.ofF32(0.0));
            case Opcodes.FCONST_1 -> stack.push(IRValue.ofF32(1.0));
            case Opcodes.FCONST_2 -> stack.push(IRValue.ofF32(2.0));
            case Opcodes.DCONST_0 -> stack.push(IRValue.ofFloat(0.0));
            case Opcodes.DCONST_1 -> stack.push(IRValue.ofFloat(1.0));

            case Opcodes.POP -> stack.pop();
            case Opcodes.POP2 -> { popMaybeWide(); }
            case Opcodes.DUP -> stack.push(stack.peek());
            case Opcodes.DUP_X1 -> dupX1();
            case Opcodes.DUP_X2 -> dupX2();
            case Opcodes.DUP2 -> dup2();
            case Opcodes.DUP2_X1 -> dup2X1();
            case Opcodes.DUP2_X2 -> dup2X2();
            case Opcodes.SWAP -> swap();

            // Categorie 4: rekenkundig/logisch
            case Opcodes.IADD, Opcodes.LADD, Opcodes.FADD, Opcodes.DADD -> binOp(IRInstruction.BinaryOp.Op.ADD);
            case Opcodes.ISUB, Opcodes.LSUB, Opcodes.FSUB, Opcodes.DSUB -> binOp(IRInstruction.BinaryOp.Op.SUB);
            case Opcodes.IMUL, Opcodes.LMUL, Opcodes.FMUL, Opcodes.DMUL -> binOp(IRInstruction.BinaryOp.Op.MUL);
            case Opcodes.IDIV, Opcodes.LDIV, Opcodes.FDIV, Opcodes.DDIV -> binOp(IRInstruction.BinaryOp.Op.DIV);
            case Opcodes.IREM, Opcodes.LREM, Opcodes.FREM, Opcodes.DREM -> binOp(IRInstruction.BinaryOp.Op.MOD);
            case Opcodes.IAND, Opcodes.LAND -> binOp(IRInstruction.BinaryOp.Op.BITAND);
            case Opcodes.IOR, Opcodes.LOR -> binOp(IRInstruction.BinaryOp.Op.BITOR);
            case Opcodes.IXOR, Opcodes.LXOR -> binOp(IRInstruction.BinaryOp.Op.BITXOR);
            case Opcodes.ISHL, Opcodes.LSHL -> binOp(IRInstruction.BinaryOp.Op.SHL);
            case Opcodes.ISHR, Opcodes.LSHR -> binOp(IRInstruction.BinaryOp.Op.SHR);
            case Opcodes.IUSHR, Opcodes.LUSHR -> binOp(IRInstruction.BinaryOp.Op.USHR);
            case Opcodes.INEG, Opcodes.LNEG, Opcodes.FNEG, Opcodes.DNEG -> negOp();
            case Opcodes.LCMP, Opcodes.FCMPL, Opcodes.FCMPG, Opcodes.DCMPL, Opcodes.DCMPG ->
                    emit3WayCompare(opcode);

            case Opcodes.I2L, Opcodes.I2F, Opcodes.I2D, Opcodes.L2I, Opcodes.L2F, Opcodes.L2D,
                 Opcodes.F2I, Opcodes.F2L, Opcodes.F2D, Opcodes.D2I, Opcodes.D2L, Opcodes.D2F,
                 Opcodes.I2B, Opcodes.I2C, Opcodes.I2S -> convert(opcode);

            // Categorie 3: arrays
            case Opcodes.ARRAYLENGTH -> stack.push(builder.emitArrayLength(stack.pop()));
            case Opcodes.IALOAD, Opcodes.LALOAD, Opcodes.FALOAD, Opcodes.DALOAD,
                 Opcodes.AALOAD, Opcodes.BALOAD, Opcodes.CALOAD, Opcodes.SALOAD -> arrayLoad(opcode);
            case Opcodes.IASTORE, Opcodes.LASTORE, Opcodes.FASTORE, Opcodes.DASTORE,
                 Opcodes.AASTORE, Opcodes.BASTORE, Opcodes.CASTORE, Opcodes.SASTORE -> arrayStore(opcode);

            // Categorie 5: controlestroom
            case Opcodes.IRETURN, Opcodes.LRETURN, Opcodes.FRETURN, Opcodes.DRETURN, Opcodes.ARETURN ->
                    builder.emitReturn(stack.pop());
            case Opcodes.RETURN -> builder.emitReturn(null);
            case Opcodes.ATHROW -> {
                final var exceptionValue = stack.pop();
                builder.emitThrow(exceptionValue, exceptionValue.type());
            }

            // Categorie 6: monitor
            case Opcodes.MONITORENTER -> builder.emitMonitorEnter(stack.pop());
            case Opcodes.MONITOREXIT -> builder.emitMonitorExit(stack.pop());

            default -> throw new UnsupportedOperationException(
                    "Opcode " + opcode + " (zero-operand) not implemented yet in "
                            + ownerQualifiedName + "." + methodNode.name);
        }
    }

    /**
     * Categorie-2-waarden (long/double) nemen ÃƒÆ'Ã‚Â©ÃƒÂ©n operand-stack-"woord" in
     * voor het bytecode-verifier-model, maar precies ÃƒÆ'Ã‚Â©ÃƒÂ©n {@link IRValue} in
     * onze eigen `stack`-simulatie (die per JVM-waarde, niet per slot, telt).
     * POP2/DUP2/DUP2_X1/DUP2_X2 hebben elk een apart gedrag naargelang hun
     * operanden categorie 1 of categorie 2 zijn (JVM-spec ÃƒÂ§dup2/dup2_x1/
     * dup2_x2) - dat onderscheid moet hier expliciet worden gemaakt, anders
     * popt een enkele long/double-waarde (verkeerd) als twee stack-items.
     */
    private boolean isWide(final IRValue v) {
        final IRType t = v.type();
        return (t instanceof IRType.Int i && i.bits() == 64)
                || (t instanceof IRType.Float f && f.bits() == 64);
    }

    private void popMaybeWide() {
        final var v1 = stack.pop();
        if (!isWide(v1)) {
            stack.pop();
        }
    }

    private void dupX1() {
        final var a = stack.pop();
        final var b = stack.pop();
        stack.push(a);
        stack.push(b);
        stack.push(a);
    }

    private void dupX2() {
        final var a = stack.pop();
        final var b = stack.pop();
        final var c = stack.pop();
        stack.push(a);
        stack.push(c);
        stack.push(b);
        stack.push(a);
    }

    private void dup2() {
        final var v1 = stack.pop();
        if (isWide(v1)) {
            // Form 2: een enkele category-2-waarde.
            stack.push(v1);
            stack.push(v1);
        } else {
            final var v2 = stack.pop();
            stack.push(v2);
            stack.push(v1);
            stack.push(v2);
            stack.push(v1);
        }
    }

    private void dup2X1() {
        final var v1 = stack.pop();
        if (isWide(v1)) {
            // Form 2: value1 (cat2) op value2 (cat1).
            final var v2 = stack.pop();
            stack.push(v1);
            stack.push(v2);
            stack.push(v1);
        } else {
            final var v2 = stack.pop();
            final var v3 = stack.pop();
            stack.push(v2);
            stack.push(v1);
            stack.push(v3);
            stack.push(v2);
            stack.push(v1);
        }
    }

    private void dup2X2() {
        final var v1 = stack.pop();
        if (isWide(v1)) {
            final var v2 = stack.pop();
            if (isWide(v2)) {
                // Form 4: value1 en value2 allebei category 2.
                stack.push(v1);
                stack.push(v2);
                stack.push(v1);
            } else {
                // Form 2: value1 (cat2), value2/value3 (cat1).
                final var v3 = stack.pop();
                stack.push(v1);
                stack.push(v3);
                stack.push(v2);
                stack.push(v1);
            }
        } else {
            final var v2 = stack.pop();
            final var v3 = stack.pop();
            if (isWide(v3)) {
                // Form 3: value1/value2 (cat1), value3 (cat2).
                stack.push(v2);
                stack.push(v1);
                stack.push(v3);
                stack.push(v2);
                stack.push(v1);
            } else {
                // Form 1: alle vier category 1.
                final var v4 = stack.pop();
                stack.push(v2);
                stack.push(v1);
                stack.push(v4);
                stack.push(v3);
                stack.push(v2);
                stack.push(v1);
            }
        }
    }

    private void swap() {
        final var a = stack.pop();
        final var b = stack.pop();
        stack.push(a);
        stack.push(b);
    }

    private void binOp(final IRInstruction.BinaryOp.Op op) {
        final var right = stack.pop();
        final var left = stack.pop();
        stack.push(builder.emitBinaryOp(op, left, right));
    }

    /**
     * {@code lcmp}/{@code fcmpl}/{@code fcmpg}/{@code dcmpl}/{@code dcmpg}:
     * een echte 3-wegvergelijking (-1/0/1) die geen rechtstreekse IR-
     * tegenhanger heeft (er is geen {@code Select}-instructie - zie
     * BYTECODE_COMPILATION_PLAN.md). Gebouwd als een alloca-cel +
     * branchy controlestroom, exact het patroon dat Nabu-source's eigen
     * ternary-expressie gebruikt ({@code IrGeneratingVisitor.
     * visitConditionalExpression}: cel aanmaken in het conditieblok,
     * per tak opslaan, in het merge-blok laden) - alleen dan drie takken
     * i.p.v. twee.
     *
     * <p>Voor {@code lcmp} is er geen NaN-geval: GT/LT dekken alles, de
     * derde tak is dan altijd exact EQ. Voor de float/double-varianten
     * kan de "geen GT, geen LT"-tak ook betekenen dat ├®├®n operand NaN is
     * (élke IEEE-754-vergelijking met NaN is false) - vandaar de extra
     * EQ-check binnen die tak: bij een echte gelijkheid 0, anders NaN,
     * wat {@code fcmpg}/{@code dcmpg} als 1 en {@code fcmpl}/{@code dcmpl}
     * als -1 moeten opleveren (JVM-spec ┬º6.5).
     */
    private void emit3WayCompare(final int opcode) {
        final var right = stack.pop();
        final var left = stack.pop();

        final boolean isFloatingPoint = opcode != Opcodes.LCMP;
        final boolean nanIsGreater = opcode == Opcodes.FCMPG || opcode == Opcodes.DCMPG;

        final var cell = builder.emitAlloca("cmp3." + uniqueBlockName(), IRType.I32);

        final var gtBlk = new IRBasicBlock(uniqueBlockName());
        final var notGtBlk = new IRBasicBlock(uniqueBlockName());
        final var ltBlk = new IRBasicBlock(uniqueBlockName());
        final var eqOrNanBlk = new IRBasicBlock(uniqueBlockName());
        final var mergeBlk = new IRBasicBlock(uniqueBlockName());
        for (final var blk : List.of(gtBlk, notGtBlk, ltBlk, eqOrNanBlk, mergeBlk)) {
            builder.currentFunction().addBlock(blk);
        }

        final var gtCond = builder.emitBinaryOp(IRInstruction.BinaryOp.Op.GT, left, right);
        builder.emitCondBranch(gtCond, gtBlk, notGtBlk);

        builder.setCurrentBlock(gtBlk);
        builder.emitStore(cell, IRValue.ofI32(1));
        builder.emitBranch(mergeBlk);

        builder.setCurrentBlock(notGtBlk);
        final var ltCond = builder.emitBinaryOp(IRInstruction.BinaryOp.Op.LT, left, right);
        builder.emitCondBranch(ltCond, ltBlk, eqOrNanBlk);

        builder.setCurrentBlock(ltBlk);
        builder.emitStore(cell, IRValue.ofI32(-1));
        builder.emitBranch(mergeBlk);

        builder.setCurrentBlock(eqOrNanBlk);
        if (isFloatingPoint) {
            final var eqTrueBlk = new IRBasicBlock(uniqueBlockName());
            final var nanBlk = new IRBasicBlock(uniqueBlockName());
            builder.currentFunction().addBlock(eqTrueBlk);
            builder.currentFunction().addBlock(nanBlk);

            final var eqCond = builder.emitBinaryOp(IRInstruction.BinaryOp.Op.EQ, left, right);
            builder.emitCondBranch(eqCond, eqTrueBlk, nanBlk);

            builder.setCurrentBlock(eqTrueBlk);
            builder.emitStore(cell, IRValue.ofI32(0));
            builder.emitBranch(mergeBlk);

            builder.setCurrentBlock(nanBlk);
            builder.emitStore(cell, IRValue.ofI32(nanIsGreater ? 1 : -1));
            builder.emitBranch(mergeBlk);
        } else {
            builder.emitStore(cell, IRValue.ofI32(0));
            builder.emitBranch(mergeBlk);
        }

        builder.setCurrentBlock(mergeBlk);
        stack.push(builder.emitLoad(cell));
    }

    private void negOp() {
        final var v = stack.pop();
        final IRInstruction.BinaryOp.Op op = IRInstruction.BinaryOp.Op.SUB;
        final IRValue zero = v.type() instanceof IRType.Float
                ? IRValue.ofFloat(0.0)
                : IRValue.ofI32(0);
        stack.push(builder.emitBinaryOp(op, zero, v));
    }

    private void convert(final int opcode) {
        final var v = stack.pop();
        final IRType target = switch (opcode) {
            case Opcodes.I2L, Opcodes.F2L, Opcodes.D2L -> IRType.I64;
            case Opcodes.I2F, Opcodes.L2F, Opcodes.D2F -> IRType.F32;
            case Opcodes.I2D, Opcodes.L2D, Opcodes.F2D -> IRType.F64;
            case Opcodes.L2I, Opcodes.F2I, Opcodes.D2I -> IRType.I32;
            case Opcodes.I2B -> IRType.I8;
            case Opcodes.I2C -> IRType.CHAR;
            case Opcodes.I2S -> IRType.I16;
            default -> throw new IllegalStateException();
        };
        stack.push(builder.emitCast(v, target));
    }

    private void arrayLoad(final int opcode) {
        final var index = stack.pop();
        final var array = stack.pop();
        final IRType elemType = switch (opcode) {
            case Opcodes.IALOAD -> IRType.I32;
            case Opcodes.LALOAD -> IRType.I64;
            case Opcodes.FALOAD -> IRType.F32;
            case Opcodes.DALOAD -> IRType.F64;
            case Opcodes.BALOAD -> IRType.I8;
            case Opcodes.CALOAD -> IRType.CHAR;
            case Opcodes.SALOAD -> IRType.I16;
            case Opcodes.AALOAD -> new IRType.Ptr(IRType.I8);
            default -> throw new IllegalStateException();
        };
        stack.push(builder.emitArrayLoad(array, index, elemType));
    }

    private void arrayStore(final int opcode) {
        final var value = stack.pop();
        final var index = stack.pop();
        final var array = stack.pop();
        final IRType elemType = switch (opcode) {
            case Opcodes.IASTORE -> IRType.I32;
            case Opcodes.LASTORE -> IRType.I64;
            case Opcodes.FASTORE -> IRType.F32;
            case Opcodes.DASTORE -> IRType.F64;
            case Opcodes.BASTORE -> IRType.I8;
            case Opcodes.CASTORE -> IRType.CHAR;
            case Opcodes.SASTORE -> IRType.I16;
            case Opcodes.AASTORE -> new IRType.Ptr(IRType.I8);
            default -> throw new IllegalStateException();
        };
        builder.emitArrayStore(array, index, value, elemType);
    }

    // --- Categorie 2: locals -------------------------------------------

    private void emitVarInsn(final int opcode, final int varIndex) {
        switch (opcode) {
            case Opcodes.ILOAD, Opcodes.LLOAD, Opcodes.FLOAD, Opcodes.DLOAD, Opcodes.ALOAD ->
                    stack.push(builder.emitLoad(slotPtr(varIndex, opcode - Opcodes.ILOAD)));
            case Opcodes.ISTORE, Opcodes.LSTORE, Opcodes.FSTORE, Opcodes.DSTORE, Opcodes.ASTORE ->
                    storeToSlot(varIndex, opcode - Opcodes.ISTORE, stack.pop());
            case Opcodes.RET -> throw new UnsupportedOperationException(
                    "RET (JSR subroutines) no longer exists since class file v51 (Java 7+) - unexpected");
            default -> throw new IllegalStateException();
        }
    }

    // De JVM hergebruikt een local-slotnummer voor variabelen van een ander
    // type zodra de scope van de vorige voorbij is (eerst een `int i`, later
    // een referentie op hetzelfde nummer). Eén alloca per slotnummer gaf dan
    // een i32-slot waaruit een referentie werd geladen ("GEP base pointer is
    // not a pointer"). Daarom één alloca per (slotnummer, type-categorie); de
    // categorie volgt uit de opcode (xLOAD/xSTORE: I, L, F, D, A in die
    // volgorde) en is dus op elke lees- en schrijfplek bekend.
    private static final int KIND_INT = 0;
    private static final int KIND_LONG = 1;
    private static final int KIND_FLOAT = 2;
    private static final int KIND_DOUBLE = 3;
    private static final int KIND_REF = 4;
    private static final String[] KIND_SUFFIX = {"", "j", "f", "d", "a"};

    private static int slotKey(final int varIndex, final int kind) {
        return varIndex * 5 + kind;
    }

    private static int kindOf(final Type type) {
        return switch (type.getSort()) {
            case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> KIND_INT;
            case Type.LONG -> KIND_LONG;
            case Type.FLOAT -> KIND_FLOAT;
            case Type.DOUBLE -> KIND_DOUBLE;
            default -> KIND_REF;
        };
    }

    private IRValue slotPtr(final int varIndex, final int kind) {
        var ptr = localSlots.get(slotKey(varIndex, kind));
        if (ptr == null) {
            // Slot dat tekstueel pas later voor het eerst geschreven wordt
            // (bereikt via een achterwaartse sprong): alvast aanmaken met het
            // canonieke type van de categorie.
            final IRType type = switch (kind) {
                case KIND_INT -> IRType.I32;
                case KIND_LONG -> IRType.I64;
                case KIND_FLOAT -> IRType.F32;
                case KIND_DOUBLE -> IRType.F64;
                default -> new IRType.Ptr(IRType.I8, "Ljava/lang/Object;");
            };
            ptr = builder.emitAlloca("l" + varIndex + KIND_SUFFIX[kind], type);
            localSlots.put(slotKey(varIndex, kind), ptr);
        }
        return ptr;
    }

    private void storeToSlot(final int varIndex, final int kind, final IRValue value) {
        // Een int-slot is altijd 32 bits, zoals in de JVM (boolean, byte,
        // char en short zijn daar ints). javac hergebruikt slots: in picocli's
        // resemblesOption staat eerst `boolean result` en later `int count` in
        // hetzelfde slot. Met het type van de eerste store werd het slot een
        // i1 en telde count alleen 0 of 1.
        final IRValue stored = kind == KIND_INT && isNarrowInt(value.type())
                ? builder.emitCast(value, IRType.I32)
                : value;
        var ptr = localSlots.get(slotKey(varIndex, kind));
        if (ptr == null) {
            ptr = builder.emitAlloca("l" + varIndex + KIND_SUFFIX[kind], stored.type());
            localSlots.put(slotKey(varIndex, kind), ptr);
        }
        builder.emitStore(ptr, stored);
    }

    private static boolean isNarrowInt(final IRType type) {
        return type instanceof IRType.Bool
                || (type instanceof IRType.Int intType && intType.bits() < 32);
    }

    private void emitIinc(final IincInsnNode insn) {
        final var ptr = slotPtr(insn.var, KIND_INT);
        final var current = builder.emitLoad(ptr);
        final var result = builder.emitBinaryOp(IRInstruction.BinaryOp.Op.ADD,
                current, IRValue.ofI32(insn.incr));
        builder.emitStore(ptr, result);
    }

    // --- Categorie 1 vervolg: int-operand & LDC -------------------------

    private void emitIntOperand(final int opcode, final int operand) {
        switch (opcode) {
            case Opcodes.BIPUSH, Opcodes.SIPUSH -> stack.push(IRValue.ofI32(operand));
            case Opcodes.NEWARRAY -> {
                final var size = stack.pop();
                final IRType elemType = DescriptorMapper.mapNewArrayType(operand);
                final String elemDesc = switch (operand) {
                    case Opcodes.T_BOOLEAN -> "Z";
                    case Opcodes.T_CHAR -> "C";
                    case Opcodes.T_FLOAT -> "F";
                    case Opcodes.T_DOUBLE -> "D";
                    case Opcodes.T_BYTE -> "B";
                    case Opcodes.T_SHORT -> "S";
                    case Opcodes.T_INT -> "I";
                    default -> "J";
                };
                stack.push(newArray(size, elemType, "[" + elemDesc));
            }
            default -> throw new IllegalStateException();
        }
    }

    /**
     * Alloceert een array en geeft die terug als array-REFERENTIE.
     * {@code IRBuilder.emitAllocaArray} typeert zijn resultaat met het
     * ELEMENT-type (een {@code new char[n]} is daar een {@code i16}); wie die
     * waarde in een local zet, kreeg een i16-slot waaruit later de array
     * "geladen" werd. De cast is voor de backend een no-op (pointer naar
     * pointer) en zet alleen het IR-type recht.
     */
    /**
     * An array allocation: like source IR, the AllocaArray carries the array
     * type (element type as pointee, array descriptor). With only the element
     * type, `new int[n][]` (elements of type int[]) and `new int[n]` looked
     * the same to the backend.
     */
    private IRValue newArray(final IRValue size, final IRType elemType, final String arrayDescriptor) {
        return builder.emitAllocaArray(size, new IRType.Ptr(elemType, arrayDescriptor));
    }

    private void emitLdc(final Object cst) {
        final IRValue value = switch (cst) {
            case Integer i -> IRValue.ofI32(i);
            case Long l -> IRValue.ofInt(l, 64);
            case Float f -> IRValue.ofF32(f);
            case Double d -> IRValue.ofFloat(d);
            case String s -> IRValue.ofString(s);
            case Type t -> new IRValue.ConstClass(new IRType.Ptr(IRType.I8, t.getDescriptor()));
            default -> throw new UnsupportedOperationException(
                    "LDC of " + (cst == null ? "null" : cst.getClass())
                            + " not implemented yet (Handle/ConstantDynamic -> invokedynamic related)");
        };
        stack.push(value);
    }

    // --- Categorie 6: objecten/velden ----------------------------------

    private void emitTypeInsn(final int opcode, final String typeDesc) {
        noteReference(typeDesc);
        switch (opcode) {
            case Opcodes.NEW -> stack.push(builder.emitHeapAlloc(typeDesc,
                    new IRType.Ptr(IRType.I8, "L" + typeDesc + ";")));
            case Opcodes.ANEWARRAY -> {
                final var size = stack.pop();
                stack.push(newArray(size,
                        new IRType.Ptr(IRType.I8, "L" + typeDesc + ";"),
                        "[" + (typeDesc.startsWith("[") ? typeDesc : "L" + typeDesc + ";")));
            }
            case Opcodes.CHECKCAST -> {
                final var v = stack.pop();
                stack.push(builder.emitCast(v, new IRType.Ptr(IRType.I8, "L" + typeDesc + ";")));
            }
            case Opcodes.INSTANCEOF -> {
                final var v = stack.pop();
                stack.push(builder.emitInstanceOf(v, new IRType.Ptr(IRType.I8, "L" + typeDesc + ";")));
            }
            default -> throw new IllegalStateException();
        }
    }

    private void emitFieldInsn(final int opcode, final FieldInsnNode insn) {
        noteReference(insn.owner);
        final IRType fieldType = DescriptorMapper.mapType(Type.getType(insn.desc));
        final String ownerName = insn.owner.replace('/', '.');
        switch (opcode) {
            case Opcodes.GETSTATIC -> stack.push(builder.emitLoad(fieldType,
                    new IRValue.Named(ownerName + "_" + insn.name, fieldType, null, true)));
            case Opcodes.PUTSTATIC -> builder.emitStore(
                    new IRValue.Named(ownerName + "_" + insn.name, fieldType, null, true),
                    stack.pop());
            case Opcodes.GETFIELD -> {
                final var receiver = stack.pop();
                // LET OP: moet Values(obj, Named(...)) zijn, niet een kale
                // Named - de native backend herkent een instantie-veldtoegang
                // uitsluitend aan die vorm (isObjectFieldAccess in
                // InstructionEmitter); een kale Named verliest het
                // ontvanger-object en degradeert naar een kapotte
                // "adres-van-de-naam"-lookup. fieldIndex blijft -1 (geen
                // ClassNode beschikbaar voor insn.owner als dat niet de
                // huidige klasse is); de backend valt dan terug op een
                // naam-gebaseerde veldlookup (classLayouts.findField).
                final var named = new IRValue.Named(insn.name, fieldType, fieldOwnerType(insn), false);
                stack.push(builder.emitLoad(fieldType, new IRValue.Values(receiver, named)));
            }
            case Opcodes.PUTFIELD -> {
                final var value = stack.pop();
                final var receiver = stack.pop();
                final var named = new IRValue.Named(insn.name, fieldType, fieldOwnerType(insn), false);
                builder.emitStore(new IRValue.Values(receiver, named), value);
            }
            default -> throw new IllegalStateException();
        }
    }

    /**
     * Het eigenaar-type van een instantie-veld zoals de instructie het noemt.
     * Niet het statische type van de receiver op de stack: dat is na een
     * stack-merge of bij een interface-getypeerde waarde te algemeen, en de
     * backend zocht het veld dan op naam in een willekeurige andere klasse
     * (tientallen klassen hebben een veld {@code next}).
     */
    private static IRType fieldOwnerType(final FieldInsnNode insn) {
        return new IRType.Ptr(IRType.I8, "L" + insn.owner + ";");
    }

    // --- Categorie 7: methode-aanroepen (geen invokedynamic) ------------

    private void emitMethodInsn(final int opcode, final MethodInsnNode insn) {
        noteReference(insn.owner);
        noteMethodReference(insn.owner, insn.name, insn.desc,
                opcode == Opcodes.INVOKEVIRTUAL || opcode == Opcodes.INVOKEINTERFACE);
        final Type methodType = Type.getMethodType(insn.desc);
        final IRType returnType = DescriptorMapper.mapType(methodType.getReturnType());
        final List<IRType> paramTypes = new ArrayList<>();
        final Type[] argTypes = methodType.getArgumentTypes();

        final CallKind callKind = switch (opcode) {
            case Opcodes.INVOKESTATIC -> CallKind.STATIC;
            case Opcodes.INVOKEVIRTUAL -> CallKind.VIRTUAL;
            case Opcodes.INVOKEINTERFACE -> CallKind.INTERFACE;
            case Opcodes.INVOKESPECIAL -> CallKind.SPECIAL;
            default -> throw new IllegalStateException();
        };

        final List<IRValue> args = new ArrayList<>();
        // Argumenten staan in omgekeerde volgorde op de stack t.o.v.
        // insertievolgorde die emitCall verwacht.
        final Deque<IRValue> tmp = new ArrayDeque<>();
        for (final Type argType : argTypes) {
            tmp.push(stack.pop());
            paramTypes.add(DescriptorMapper.mapType(argType));
        }
        while (!tmp.isEmpty()) {
            args.add(tmp.pop());
        }

        if (callKind != CallKind.STATIC) {
            args.add(0, stack.pop()); // receiver ('this')
        }

        final String ownerName = insn.owner.replace('/', '.');
        String fullName = ownerName + "_" + insn.name;
        if ("<init>".equals(insn.name)) {
            fullName = ownerName + "_init";
        }

        final IRValue result = builder.emitCall(callKind, fullName, returnType, paramTypes, args);
        if (!(returnType instanceof IRType.Void)) {
            stack.push(result);
        }
    }

    // --- Categorie 5: sprongen (zonder switch/exceptions) ---------------

    private void emitJumpInsn(final int opcode, final JumpInsnNode insn, final Frame<BasicValue> frameBefore) {
        final IRBasicBlock target = blocksByAsmLabel.get(insn.label.getLabel());

        if (opcode == Opcodes.GOTO) {
            storeStackForEdge(insn.label);
            builder.emitBranch(target);
            return;
        }

        // Voor elke andere jump-opcode: eerst de voorwaarde als IRValue
        // opbouwen, dan emitCondBranch. De "else"-tak is de volgende
        // instructie in programmavolgorde: we maken daarvoor een blok aan
        // dat we meteen na deze branch openen (fallthrough-blok).
        final IRValue condition = buildJumpCondition(opcode);
        // Wat ná het poppen van de operanden nog op de stack staat, reist mee
        // naar het sprongdoel.
        storeStackForEdge(insn.label);
        final IRBasicBlock fallthrough = new IRBasicBlock(uniqueBlockName());
        builder.emitCondBranch(condition, target, fallthrough);
        builder.currentFunction().addBlock(fallthrough);
        builder.setCurrentBlock(fallthrough);
    }

    private IRValue buildJumpCondition(final int opcode) {
        return switch (opcode) {
            case Opcodes.IFEQ -> cmpZero(IRInstruction.BinaryOp.Op.EQ);
            case Opcodes.IFNE -> cmpZero(IRInstruction.BinaryOp.Op.NEQ);
            case Opcodes.IFLT -> cmpZero(IRInstruction.BinaryOp.Op.LT);
            case Opcodes.IFGE -> cmpZero(IRInstruction.BinaryOp.Op.GTE);
            case Opcodes.IFGT -> cmpZero(IRInstruction.BinaryOp.Op.GT);
            case Opcodes.IFLE -> cmpZero(IRInstruction.BinaryOp.Op.LTE);
            case Opcodes.IF_ICMPEQ, Opcodes.IF_ACMPEQ -> cmpTwo(IRInstruction.BinaryOp.Op.EQ);
            case Opcodes.IF_ICMPNE, Opcodes.IF_ACMPNE -> cmpTwo(IRInstruction.BinaryOp.Op.NEQ);
            case Opcodes.IF_ICMPLT -> cmpTwo(IRInstruction.BinaryOp.Op.LT);
            case Opcodes.IF_ICMPGE -> cmpTwo(IRInstruction.BinaryOp.Op.GTE);
            case Opcodes.IF_ICMPGT -> cmpTwo(IRInstruction.BinaryOp.Op.GT);
            case Opcodes.IF_ICMPLE -> cmpTwo(IRInstruction.BinaryOp.Op.LTE);
            case Opcodes.IFNULL -> cmpZero(IRInstruction.BinaryOp.Op.EQ);
            case Opcodes.IFNONNULL -> cmpZero(IRInstruction.BinaryOp.Op.NEQ);
            default -> throw new IllegalStateException("Unknown jump opcode " + opcode);
        };
    }

    private IRValue cmpZero(final IRInstruction.BinaryOp.Op op) {
        final var v = stack.pop();
        final IRValue zero = v.type() instanceof IRType.Ptr
                ? IRValue.nullPtr(((IRType.Ptr) v.type()).pointee())
                : IRValue.ofI32(0);
        return builder.emitBinaryOp(op, v, zero);
    }

    private IRValue cmpTwo(final IRInstruction.BinaryOp.Op op) {
        final var right = stack.pop();
        final var left = stack.pop();
        return builder.emitBinaryOp(op, left, right);
    }

    // --- Categorie 5 vervolg: tableswitch/lookupswitch ------------------
    //
    // Geen speciale IR-switch-instructie beschikbaar (zie IRInstruction) -
    // net als Nabu-source's eigen switch-statement (IrGeneratingVisitor.
    // visitSwitchStatement) wordt dit vertaald naar een keten van EQ-
    // vergelijkingen met conditionele branches. De case-/default-doelblokken
    // zijn al aangemaakt door preCreateJumpTargetBlocks(); hier worden alleen
    // de TUSSENLIGGENDE vergelijkingsblokken aangemaakt.

    private void emitTableSwitch(final TableSwitchInsnNode insn) {
        final IRValue selector = stack.pop();
        storeStackForEdge(insn.dflt);
        insn.labels.forEach(this::storeStackForEdge);
        final IRBasicBlock defaultBlk = blocksByAsmLabel.get(insn.dflt.getLabel());
        final int n = insn.labels.size();
        if (n == 0) {
            builder.emitBranch(defaultBlk);
            return;
        }
        final List<IRBasicBlock> cmpBlocks = buildCmpBlocks(n);
        for (int i = 0; i < n; i++) {
            builder.setCurrentBlock(cmpBlocks.get(i));
            final int key = insn.min + i;
            final IRBasicBlock target = blocksByAsmLabel.get(insn.labels.get(i).getLabel());
            final IRBasicBlock next = (i + 1 < n) ? cmpBlocks.get(i + 1) : defaultBlk;
            final IRValue eq = builder.emitBinaryOp(IRInstruction.BinaryOp.Op.EQ, selector, IRValue.ofI32(key));
            builder.emitCondBranch(eq, target, next);
        }
    }

    private void emitLookupSwitch(final LookupSwitchInsnNode insn) {
        final IRValue selector = stack.pop();
        storeStackForEdge(insn.dflt);
        insn.labels.forEach(this::storeStackForEdge);
        final IRBasicBlock defaultBlk = blocksByAsmLabel.get(insn.dflt.getLabel());
        final int n = insn.labels.size();
        if (n == 0) {
            builder.emitBranch(defaultBlk);
            return;
        }
        final List<IRBasicBlock> cmpBlocks = buildCmpBlocks(n);
        for (int i = 0; i < n; i++) {
            builder.setCurrentBlock(cmpBlocks.get(i));
            final int key = insn.keys.get(i);
            final IRBasicBlock target = blocksByAsmLabel.get(insn.labels.get(i).getLabel());
            final IRBasicBlock next = (i + 1 < n) ? cmpBlocks.get(i + 1) : defaultBlk;
            final IRValue eq = builder.emitBinaryOp(IRInstruction.BinaryOp.Op.EQ, selector, IRValue.ofI32(key));
            builder.emitCondBranch(eq, target, next);
        }
    }

    /**
     * Het huidige blok dient als eerste vergelijkingsblok (zoals
     * visitSwitchStatement's "entryBlk -> cmpBlocks.get(0)" ook impliciet
     * is - hier is er geen losse entry-branch nodig omdat currentBlock al
     * leeg/open is op het moment dat de switch-opcode wordt verwerkt).
     */
    private List<IRBasicBlock> buildCmpBlocks(final int n) {
        final List<IRBasicBlock> cmpBlocks = new ArrayList<>(n);
        cmpBlocks.add(builder.currentBlock());
        for (int i = 1; i < n; i++) {
            final IRBasicBlock blk = new IRBasicBlock(uniqueBlockName());
            builder.currentFunction().addBlock(blk);
            cmpBlocks.add(blk);
        }
        return cmpBlocks;
    }

    // --- Categorie 3 vervolg: multianewarray ----------------------------
    //
    // Geen runtime-helper voor directe N-dimensionale allocatie - net als
    // `new T[a][b]` in Nabu-source zelf zou doen, wordt dit opgebouwd uit
    // geneste enkel-dimensionale allocaties (emitAllocaArray) plus een
    // echte runtime-lus (de groottes zijn pas bij uitvoering bekend, dus
    // dit kan niet worden "uitgerold" op compileertijd). `insn.dims` kan
    // KLEINER zijn dan het aantal dimensies in de descriptor (bv.
    // `new int[n][][]` heeft dims=1 maar 3 dimensies in de descriptor) -
    // de niet-expliciet-gesizede binnenste niveaus blijven dan op hun
    // default (null voor referentie-arrays), exact zoals de JVM-spec het
    // voorschrijft.

    private void emitMultiANewArray(final MultiANewArrayInsnNode insn) {
        final Type fullType = Type.getType(insn.desc);
        final Type elemLeaf = fullType.getElementType();
        if (elemLeaf.getSort() == Type.OBJECT) {
            noteReference(elemLeaf.getInternalName());
        }

        final List<IRValue> sizes = new ArrayList<>();
        final Deque<IRValue> tmp = new ArrayDeque<>();
        for (int i = 0; i < insn.dims; i++) {
            tmp.push(stack.pop());
        }
        while (!tmp.isEmpty()) {
            sizes.add(tmp.pop());
        }

        stack.push(allocateMultiDimArray(insn.desc, sizes, 0));
    }

    private IRValue allocateMultiDimArray(final String desc,
                                          final List<IRValue> sizes,
                                          final int depth) {
        final String elemDesc = desc.substring(1);
        final IRType elemIrType = DescriptorMapper.mapType(Type.getType(elemDesc));
        final IRValue size = sizes.get(depth);
        final IRValue array = newArray(size, elemIrType, desc);

        final boolean hasMoreDims = elemDesc.charAt(0) == '[';
        final boolean hasMoreSizes = depth + 1 < sizes.size();
        if (!hasMoreDims || !hasMoreSizes) {
            return array;
        }

        final var idxCell = builder.emitAlloca("mdim." + uniqueBlockName(), IRType.I32);
        builder.emitStore(idxCell, IRValue.ofI32(0));

        final var condBlk = new IRBasicBlock(uniqueBlockName());
        final var bodyBlk = new IRBasicBlock(uniqueBlockName());
        final var exitBlk = new IRBasicBlock(uniqueBlockName());
        builder.currentFunction().addBlock(condBlk);
        builder.currentFunction().addBlock(bodyBlk);
        builder.currentFunction().addBlock(exitBlk);

        builder.emitBranch(condBlk);

        builder.setCurrentBlock(condBlk);
        final var idx = builder.emitLoad(idxCell);
        final var cond = builder.emitBinaryOp(IRInstruction.BinaryOp.Op.LT, idx, size);
        builder.emitCondBranch(cond, bodyBlk, exitBlk);

        builder.setCurrentBlock(bodyBlk);
        final var inner = allocateMultiDimArray(elemDesc, sizes, depth + 1);
        builder.emitArrayStore(array, builder.emitLoad(idxCell), inner, elemIrType);
        final var next = builder.emitBinaryOp(IRInstruction.BinaryOp.Op.ADD,
                builder.emitLoad(idxCell), IRValue.ofI32(1));
        builder.emitStore(idxCell, next);
        builder.emitBranch(condBlk);

        builder.setCurrentBlock(exitBlk);
        return array;
    }

    // --- invokedynamic: echte bootstrap-mechanismen -----------------------
    //
    // Geen VM-niveau MethodHandle/CallSite-linking (dat zou een complete
    // java.lang.invoke-runtime in C vergen); in plaats daarvan worden de
    // TWEE bootstraps die in de praktijk door javac worden gebruikt
    // (StringConcatFactory en LambdaMetafactory) AOT naar hun daadwerkelijke
    // semantiek vertaald, net zoals javac dat vóór Java 9 zelf deed
    // (StringBuilder-ketens) resp. hoe de JVM runtime een lambda-klasse
    // spint - alleen dan tijdens compileren i.p.v. lazy bij uitvoering. Dit
    // is de "echte bootstrap"-aanpak die voor deze sessie is gekozen, in
    // plaats van een vereenvoudigde decompilatie-benadering.

    private void emitInvokeDynamicInsn(final InvokeDynamicInsnNode insn) {
        final var bsm = insn.bsm;
        final String bsmOwner = bsm.getOwner();
        final String bsmName = bsm.getName();

        if ("java/lang/invoke/StringConcatFactory".equals(bsmOwner)
                && ("makeConcatWithConstants".equals(bsmName) || "makeConcat".equals(bsmName))) {
            emitStringConcatIndy(insn);
            return;
        }
        if ("java/lang/invoke/LambdaMetafactory".equals(bsmOwner)
                && ("metafactory".equals(bsmName) || "altMetafactory".equals(bsmName))) {
            emitLambdaMetafactoryIndy(insn);
            return;
        }
        if ("java/lang/runtime/ObjectMethods".equals(bsmOwner) && "bootstrap".equals(bsmName)) {
            emitObjectMethodsBootstrap(insn);
            return;
        }
        if ("java/lang/runtime/SwitchBootstraps".equals(bsmOwner) && "typeSwitch".equals(bsmName)) {
            emitTypeSwitchBootstrap(insn);
            return;
        }
        throw new UnsupportedOperationException(
                "invokedynamic with an unknown/unsupported bootstrap method is not "
                        + "implemented yet: "
                        + bsmOwner.replace('/', '.') + "." + bsmName
                        + " (call-site: " + ownerQualifiedName + "." + methodNode.name + ")");
    }

    /**
     * {@code StringConcatFactory.makeConcatWithConstants}/{@code makeConcat}:
     * de "recipe" (een String met {@code \u0001} als plekhouder voor een
     * dynamisch argument) is op linktijd al volledig bekend (bsm-argument),
     * dus wordt hier direct vertaald naar een keten van {@code ADD}-operaties
     * op String-operanden - exact hetzelfde mechanisme ({@code nabu_concat})
     * dat Nabu-source-code's eigen {@code "" + x}-concatenatie gebruikt (zie
     * {@code InstructionEmitter.emitStringConcat}). De keten start altijd met
     * de lege string zodat de concat-herkenning (die op "minstens één
     * String-operand" let) ook afvuurt wanneer het eerste segment toevallig
     * een primitief dynamisch argument is.
     */
    private void emitStringConcatIndy(final InvokeDynamicInsnNode insn) {
        final Type methodType = Type.getMethodType(insn.desc);
        final Type[] argTypes = methodType.getArgumentTypes();

        final List<IRValue> args = new ArrayList<>();
        final Deque<IRValue> tmp = new ArrayDeque<>();
        for (int i = 0; i < argTypes.length; i++) {
            tmp.push(stack.pop());
        }
        while (!tmp.isEmpty()) {
            args.add(tmp.pop());
        }

        final String recipe;
        if ("makeConcatWithConstants".equals(insn.bsm.getName())) {
            if (insn.bsmArgs.length == 0 || !(insn.bsmArgs[0] instanceof String)) {
                throw new UnsupportedOperationException(
                        "StringConcatFactory.makeConcatWithConstants without a recipe string "
                                + "(bsmArgs[0]) is not supported yet: "
                                + ownerQualifiedName + "." + methodNode.name);
            }
            recipe = (String) insn.bsmArgs[0];
            if (insn.bsmArgs.length > 1) {
                // -plekhouders in de recipe verwijzen naar extra
                // bsm-constanten (bv. Class-/MethodHandle-constanten die niet
                // als gewone ldc kunnen) - komt in de praktijk niet voor bij
                // simpele String-concatenatie van locals/literals.
                throw new UnsupportedOperationException(
                        "StringConcatFactory.makeConcatWithConstants with extra constant "
                                + "bsm args (\u0002 placeholders) is not supported yet: "
                                + ownerQualifiedName + "." + methodNode.name);
            }
        } else {
            final var sb = new StringBuilder();
            for (int i = 0; i < argTypes.length; i++) {
                sb.append('');
            }
            recipe = sb.toString();
        }

        IRValue acc = builder.constString("");
        final StringBuilder literal = new StringBuilder();
        int argIndex = 0;

        for (int i = 0; i < recipe.length(); i++) {
            final char c = recipe.charAt(i);
            if (c == '') {
                if (literal.length() > 0) {
                    acc = builder.emitBinaryOp(IRInstruction.BinaryOp.Op.ADD, acc,
                            builder.constString(literal.toString()));
                    literal.setLength(0);
                }
                acc = builder.emitBinaryOp(IRInstruction.BinaryOp.Op.ADD, acc, args.get(argIndex++));
            } else if (c == '') {
                throw new UnsupportedOperationException(
                        "StringConcatFactory recipe with \u0002 (non-ldc-able constant) "
                                + "is not supported yet: " + ownerQualifiedName + "." + methodNode.name);
            } else {
                literal.append(c);
            }
        }
        if (literal.length() > 0) {
            acc = builder.emitBinaryOp(IRInstruction.BinaryOp.Op.ADD, acc,
                    builder.constString(literal.toString()));
        }

        stack.push(acc);
    }

    /**
     * {@code LambdaMetafactory.metafactory}: synthetiseert een echte klasse
     * die de functionele interface implementeert (zie
     * {@link LambdaClassSynthesizer}) - de ahead-of-time-tegenhanger van wat
     * de JVM-runtime normaal lazy doet (een hidden class spinnen) - en
     * vervangt de invokedynamic-call-site door een {@code NEW} + constructor-
     * aanroep van die klasse, analoog aan hoe een gewone {@code new}-
     * expressie elders in deze lowering wordt behandeld.
     */
    private void emitLambdaMetafactoryIndy(final InvokeDynamicInsnNode insn) {
        final Type factoryType = Type.getMethodType(insn.desc);
        final Type[] capturedArgTypes = factoryType.getArgumentTypes();

        final List<IRValue> capturedArgs = new ArrayList<>();
        final Deque<IRValue> tmp = new ArrayDeque<>();
        for (int i = 0; i < capturedArgTypes.length; i++) {
            tmp.push(stack.pop());
        }
        while (!tmp.isEmpty()) {
            capturedArgs.add(tmp.pop());
        }

        final var result = LambdaClassSynthesizer.synthesize(
                ownerQualifiedName, insn.name, insn.desc, insn.bsmArgs, this::noteReference,
                referencedMethods::add);
        extraModules.add(result.module());

        final var newObjType = new IRType.Ptr(IRType.I8, "L" + result.internalName() + ";");
        final var newObj = builder.emitHeapAlloc(result.internalName(), newObjType);

        final List<IRValue> ctorArgs = new ArrayList<>();
        ctorArgs.add(newObj);
        ctorArgs.addAll(capturedArgs);
        builder.emitCall(CallKind.SPECIAL, result.qualifiedName() + "_init", IRType.VOID,
                result.capturedTypes(), ctorArgs);

        final String functionalInterfaceInternalName = factoryType.getReturnType().getInternalName();
        stack.push(builder.emitCast(newObj,
                new IRType.Ptr(IRType.I8, "L" + functionalInterfaceInternalName + ";")));
    }

    /**
     * {@code java.lang.runtime.ObjectMethods.bootstrap}: de bootstrap die
     * javac voor een {@code record}-klasse genereert voor de drie
     * gedelegeerde methoden {@code equals}/{@code hashCode}/{@code
     * toString}. In tegenstelling tot de twee bootstraps hierboven bestaat
     * er voor dit mechanisme GEEN bestaand patroon in Nabu's eigen
     * source-route om te spiegelen (Nabu's eigen record-ondersteuning
     * genereert deze methode-lichamen niet automatisch), dus dit is een
     * oorspronkelijke implementatie - functioneel gelijkwaardig aan wat
     * {@code ObjectMethods} runtime zou opleveren, maar AOT tijdens
     * compileren, net als de StringConcatFactory/LambdaMetafactory-aanpak
     * hierboven.
     *
     * <p>bsm-argumenten: {@code [recordClass, "name1;name2;...", getter0,
     * getter1, ...]} - elke getter is een {@code H_INVOKEVIRTUAL}-Handle
     * naar de accessor-methode van dat record-veld.
     */
    private void emitObjectMethodsBootstrap(final InvokeDynamicInsnNode insn) {
        if (insn.bsmArgs.length < 2
                || !(insn.bsmArgs[0] instanceof Type recordClassConst)
                || !(insn.bsmArgs[1] instanceof String namesArg)) {
            throw new UnsupportedOperationException(
                    "ObjectMethods.bootstrap without recordClass/names constants "
                            + "(bsmArgs[0]/[1]) is not supported yet: "
                            + ownerQualifiedName + "." + methodNode.name);
        }
        final String recordInternalName = recordClassConst.getInternalName();
        final String[] fieldNames = namesArg.isEmpty() ? new String[0] : namesArg.split(";");

        final List<Handle> getters = new ArrayList<>();
        for (int i = 2; i < insn.bsmArgs.length; i++) {
            if (!(insn.bsmArgs[i] instanceof Handle getter)) {
                throw new UnsupportedOperationException(
                        "ObjectMethods.bootstrap with a non-Handle getter argument "
                                + "is not supported yet: " + ownerQualifiedName + "." + methodNode.name);
            }
            getters.add(getter);
            noteReference(getter.getOwner());
        }
        if (getters.size() != fieldNames.length) {
            throw new UnsupportedOperationException(
                    "ObjectMethods.bootstrap: number of getters (" + getters.size()
                            + ") does not match the number of names (" + fieldNames.length
                            + "): " + ownerQualifiedName + "." + methodNode.name);
        }

        switch (insn.name) {
            case "toString" -> {
                final IRValue self = stack.pop();
                stack.push(emitRecordToString(recordInternalName, self, fieldNames, getters));
            }
            case "hashCode" -> {
                final IRValue self = stack.pop();
                stack.push(emitRecordHashCode(self, getters));
            }
            case "equals" -> {
                final IRValue other = stack.pop();
                final IRValue self = stack.pop();
                stack.push(emitRecordEquals(recordInternalName, self, other, getters));
            }
            default -> throw new UnsupportedOperationException(
                    "ObjectMethods.bootstrap with unknown method name '" + insn.name
                            + "' is not supported yet: " + ownerQualifiedName + "." + methodNode.name);
        }
    }

    /**
     * Haalt de waarde van een record-veld op via zijn "getter"-Handle uit
     * {@code ObjectMethods.bootstrap}'s bsm-args. Deze Handle heeft in de
     * praktijk EEN van twee vormen, afhankelijk van de javac-versie/
     * optimalisatie:
     * <ul>
     *   <li>{@code H_INVOKEVIRTUAL} naar de publieke accessor-methode
     *       ({@code getDesc()} is dan een echt methode-descriptor, bv.
     *       {@code "()Ljava/lang/String;"}) - roep de methode aan;</li>
     *   <li>{@code H_GETFIELD} direct naar het private veld (optimalisatie
     *       die de accessor-aanroep overslaat; {@code getDesc()} is dan
     *       gewoon het VELD-type-descriptor, bv. {@code "Ljava/util/Locale;"}
     *       - geen methode-descriptor, dus {@code Type.getMethodType()}
     *       hierop toepassen crasht) - lees het veld direct, exact zoals
     *       de GETFIELD-opcode elders in deze lowering (zie
     *       {@code emitFieldInsn}).</li>
     * </ul>
     */
    private IRValue callGetter(final IRValue receiver, final Handle getter) {
        if (getter.getTag() == Opcodes.H_GETFIELD) {
            final IRType fieldType = DescriptorMapper.mapType(Type.getType(getter.getDesc()));
            final var named = new IRValue.Named(getter.getName(), fieldType, receiver.type(), false);
            return builder.emitLoad(fieldType, new IRValue.Values(receiver, named));
        }
        final String owner = getter.getOwner().replace('/', '.');
        noteMethodReference(getter.getOwner(), getter.getName(), getter.getDesc(), true);
        final Type getterMethodType = Type.getMethodType(getter.getDesc());
        final IRType returnType = DescriptorMapper.mapType(getterMethodType.getReturnType());
        return builder.emitCall(CallKind.VIRTUAL, owner + "_" + getter.getName(), returnType,
                List.of(), List.of(receiver));
    }

    /**
     * Haalt de statisch gedeclareerde eigenaarklasse op uit een object-
     * {@code IRType.Ptr}'s JVM-descriptor (bv. {@code "Ljava/lang/Object;"}
     * -> {@code "java.lang.Object"}) - dezelfde vorm die {@code
     * emitMethodInsn()} gebruikt om een gewone INVOKEVIRTUAL-call-naam op
     * te bouwen.
     */
    /**
     * Zelfde als {@link #ownerFromPtr(IRType.Ptr)}, maar levert de interne
     * (slash-)vorm op i.p.v. de dot-vorm - nodig voor {@link #noteMethodReference}
     * (dat dezelfde vorm verwacht als ASM's {@code MethodInsnNode.owner}).
     */
    private String internalOwnerFromPtr(final IRType.Ptr ptr) {
        final String descriptor = ptr.jvmDescriptor();
        if (descriptor == null || descriptor.length() < 2
                || descriptor.charAt(0) != 'L' || !descriptor.endsWith(";")) {
            return null;
        }
        return descriptor.substring(1, descriptor.length() - 1);
    }

    private String ownerFromPtr(final IRType.Ptr ptr) {
        final String descriptor = ptr.jvmDescriptor();
        if (descriptor == null || descriptor.length() < 2
                || descriptor.charAt(0) != 'L' || !descriptor.endsWith(";")) {
            throw new UnsupportedOperationException(
                    "ObjectMethods.bootstrap for array/unknown object type " + descriptor
                            + " is not supported yet: " + ownerQualifiedName + "." + methodNode.name);
        }
        return descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
    }

    /**
     * {@code toString()}: {@code SimpleName[field1=value1, field2=value2]},
     * exact het formaat dat {@code java.lang.runtime.ObjectMethods} ook
     * oplevert. Hergebruikt dezelfde {@code nabu_concat}-ADD-keten als
     * {@link #emitStringConcatIndy} - de native backend zet elke
     * veldwaarde, ongeacht type, zelf om naar zijn string-vorm zodra een
     * van de ADD-operanden een String is.
     */
    private IRValue emitRecordToString(final String recordInternalName, final IRValue self,
                                        final String[] fieldNames, final List<Handle> getters) {
        final int slash = recordInternalName.lastIndexOf('/');
        final String simpleName = slash < 0
                ? recordInternalName
                : recordInternalName.substring(slash + 1);

        IRValue acc = builder.constString(simpleName + "[");
        for (int i = 0; i < fieldNames.length; i++) {
            if (i > 0) {
                acc = builder.emitBinaryOp(IRInstruction.BinaryOp.Op.ADD, acc, builder.constString(", "));
            }
            acc = builder.emitBinaryOp(IRInstruction.BinaryOp.Op.ADD, acc,
                    builder.constString(fieldNames[i] + "="));
            acc = builder.emitBinaryOp(IRInstruction.BinaryOp.Op.ADD, acc,
                    callGetter(self, getters.get(i)));
        }
        return builder.emitBinaryOp(IRInstruction.BinaryOp.Op.ADD, acc, builder.constString("]"));
    }

    /**
     * {@code hashCode()}: {@code hash = hash * 31 + fieldHash} over alle
     * velden, in declaratievolgorde - dezelfde combinatievorm die
     * {@code java.util.Objects.hash}/{@code List.hashCode} gebruiken. De
     * exacte waarde hoeft niet bit-voor-bit gelijk te zijn aan wat de
     * OpenJDK-runtime zou opleveren (dat is geen contractuele eis - enkel
     * consistentie met {@code equals()} binnen dit gecompileerde programma
     * is vereist).
     */
    private IRValue emitRecordHashCode(final IRValue self, final List<Handle> getters) {
        IRValue hash = IRValue.ofI32(0);
        for (final Handle getter : getters) {
            final IRValue fieldValue = callGetter(self, getter);
            final IRValue fieldHash = fieldHashCode(fieldValue);
            final IRValue scaled = builder.emitBinaryOp(
                    IRInstruction.BinaryOp.Op.MUL, hash, IRValue.ofI32(31));
            hash = builder.emitBinaryOp(IRInstruction.BinaryOp.Op.ADD, scaled, fieldHash);
        }
        return hash;
    }

    /**
     * Per-veldtype hashCode-bijdrage, analoog aan de boxed-wrapperklassen
     * (Integer/Long/Boolean/Float/Double) se eigen {@code hashCode()}.
     * String is het enige referentietype dat momenteel ondersteund wordt
     * (via de bestaande {@code java.lang.String_hashCode}-runtimehelper);
     * elk ander objecttype zou een polymorfe {@code hashCode()}-dispatch
     * vereisen die vanuit bytecode-lowering nog niet beschikbaar is.
     */
    private IRValue fieldHashCode(final IRValue value) {
        final IRType type = value.type();
        if (type instanceof IRType.Ptr ptr) {
            final String descriptor = ptr.jvmDescriptor();
            if (descriptor != null && descriptor.startsWith("[")) {
                // Array-velden: records (en dus ObjectMethods.bootstrap)
                // gebruiken Arrays.hashCode()-semantiek i.p.v. de
                // identity-hashCode die arrays anders zouden opleveren
                // (arrays overriden Object.hashCode() niet) - anders zou
                // een record met inhoudelijk gelijke array-velden toch een
                // andere hashCode krijgen.
                return arrayHashCode(value, descriptor);
            }
            // Generiek: de statisch gedeclareerde eigenaar van het veld
            // (precies zoals emitMethodInsn() dat ook doet voor een gewone
            // INVOKEVIRTUAL) levert de call-naam op; virtuele dispatch
            // (vtable) zorgt er zelf voor dat dit op de ECHTE hashCode()-
            // override van de runtime-waarde uitkomt, of die nu String,
            // List, een ander JCL-type of een Nabu-klasse is - dus geen
            // per-type speciale afhandeling nodig.
            noteMethodReference(internalOwnerFromPtr(ptr), "hashCode", "()I", true);
            return builder.emitCall(CallKind.VIRTUAL, ownerFromPtr(ptr) + "_hashCode", IRType.I32,
                    List.of(), List.of(value));
        }
        if (type == IRType.BOOL) {
            return selectI32(value, IRValue.ofI32(1231), IRValue.ofI32(1237));
        }
        if (type instanceof IRType.Int i && i.bits() == 64) {
            final IRValue shifted = builder.emitBinaryOp(
                    IRInstruction.BinaryOp.Op.USHR, value, IRValue.ofI32(32));
            final IRValue folded = builder.emitBinaryOp(
                    IRInstruction.BinaryOp.Op.BITXOR, value, shifted);
            return builder.emitCast(folded, IRType.I32);
        }
        if (type instanceof IRType.Int) {
            return builder.emitCast(value, IRType.I32);
        }
        if (type instanceof IRType.Float) {
            // Float/Double.hashCode is gebaseerd op de IEEE-754 bitpatronen
            // (floatToIntBits/doubleToLongBits); een bit-reinterpretatie-
            // cast is in Nabu's IR nog niet beschikbaar, dus benaderen we
            // dit met een waardeconversie - voldoende voor consistentie
            // binnen dit gecompileerde programma.
            return builder.emitCast(value, IRType.I32);
        }
        throw new UnsupportedOperationException(
                "ObjectMethods.bootstrap hashCode for field type " + type
                        + " is not supported yet: " + ownerQualifiedName + "." + methodNode.name);
    }

    /**
     * {@code equals()}: {@code instanceof}-check gevolgd door een
     * veld-voor-veld-vergelijking (AND-gecombineerd), exact zoals de echte
     * record-gegenereerde equals-methode.
     */
    private IRValue emitRecordEquals(final String recordInternalName, final IRValue self,
                                      final IRValue other, final List<Handle> getters) {
        final var recordType = new IRType.Ptr(IRType.I8, "L" + recordInternalName + ";");
        final IRValue isInstance = builder.emitInstanceOf(other, recordType);

        final var sameTypeBlk = new IRBasicBlock(uniqueBlockName());
        final var notSameTypeBlk = new IRBasicBlock(uniqueBlockName());
        final var mergeBlk = new IRBasicBlock(uniqueBlockName());
        for (final var blk : List.of(sameTypeBlk, notSameTypeBlk, mergeBlk)) {
            builder.currentFunction().addBlock(blk);
        }
        final var cell = builder.emitAlloca("recEq." + uniqueBlockName(), IRType.BOOL);

        builder.emitCondBranch(isInstance, sameTypeBlk, notSameTypeBlk);

        builder.setCurrentBlock(notSameTypeBlk);
        builder.emitStore(cell, IRValue.ofBool(false));
        builder.emitBranch(mergeBlk);

        builder.setCurrentBlock(sameTypeBlk);
        final IRValue otherTyped = builder.emitCast(other, recordType);
        IRValue allEqual = IRValue.ofBool(true);
        for (final Handle getter : getters) {
            final IRValue a = callGetter(self, getter);
            final IRValue b = callGetter(otherTyped, getter);
            allEqual = builder.emitBinaryOp(IRInstruction.BinaryOp.Op.AND, allEqual, fieldEquals(a, b));
        }
        builder.emitStore(cell, allEqual);
        builder.emitBranch(mergeBlk);

        builder.setCurrentBlock(mergeBlk);
        return builder.emitLoad(cell);
    }

    /**
     * Veld-voor-veld gelijkheid: primitieven via {@code EQ}, String via de
     * bestaande {@code java.lang.String_equals}-runtimehelper (dezelfde
     * null-veilige {@code jcl_eq}-semantiek als overal elders in de JCL).
     */
    // De ene expliciete parameter van equals(Object). Met een lege
    // parameterlijst declareerde de backend `X_equals(this)` en kreeg de call
    // er toch twee operanden bij ("Incorrect number of arguments").
    private static final IRType OBJECT_PARAM = new IRType.Ptr(IRType.I8, "Ljava/lang/Object;");

    private IRValue fieldEquals(final IRValue a, final IRValue b) {
        final IRType type = a.type();
        if (type instanceof IRType.Ptr ptr) {
            final String descriptor = ptr.jvmDescriptor();
            if (descriptor != null && descriptor.startsWith("[")) {
                // Zelfde reden als in fieldHashCode(): Arrays.equals()-
                // semantiek (lengte + element-voor-element), niet de
                // referentie-gelijkheid die Object.equals() op arrays zou
                // geven.
                return arrayEquals(a, b, descriptor);
            }
            // Zelfde generieke redenering als fieldHashCode(): de statische
            // eigenaar levert de call-naam, virtuele dispatch doet de rest.
            noteMethodReference(internalOwnerFromPtr(ptr), "equals", "(Ljava/lang/Object;)Z", true);
            return builder.emitCall(CallKind.VIRTUAL, ownerFromPtr(ptr) + "_equals", IRType.BOOL,
                    List.of(OBJECT_PARAM), List.of(a, b));
        }
        return builder.emitBinaryOp(IRInstruction.BinaryOp.Op.EQ, a, b);
    }

    /**
     * Kiest tussen twee {@code i32}-waarden op basis van een booleaanse
     * conditie - er bestaat geen {@code Select}-instructie in Nabu's IR,
     * dus dit wordt (net als {@link #emit3WayCompare}) opgebouwd met een
     * alloca-cel en een branch/merge-blokpaar.
     */
    private IRValue selectI32(final IRValue cond, final IRValue trueVal, final IRValue falseVal) {
        final var cell = builder.emitAlloca("sel." + uniqueBlockName(), IRType.I32);
        final var trueBlk = new IRBasicBlock(uniqueBlockName());
        final var falseBlk = new IRBasicBlock(uniqueBlockName());
        final var mergeBlk = new IRBasicBlock(uniqueBlockName());
        for (final var blk : List.of(trueBlk, falseBlk, mergeBlk)) {
            builder.currentFunction().addBlock(blk);
        }
        builder.emitCondBranch(cond, trueBlk, falseBlk);

        builder.setCurrentBlock(trueBlk);
        builder.emitStore(cell, trueVal);
        builder.emitBranch(mergeBlk);

        builder.setCurrentBlock(falseBlk);
        builder.emitStore(cell, falseVal);
        builder.emitBranch(mergeBlk);

        builder.setCurrentBlock(mergeBlk);
        return builder.emitLoad(cell);
    }

    /**
     * {@code java.lang.runtime.SwitchBootstraps.typeSwitch}: de bootstrap
     * voor pattern-matching {@code switch} (JEP 441/420). De call-site heeft
     * als operanden het te testen object en een {@code startIndex} (voor het
     * hervatten na een "guarded pattern" die niet matchte), en levert de
     * index op van het eerste label (vanaf {@code startIndex}) dat matcht,
     * of {@code labels.length} als niets matcht - exact zoals de echte
     * runtime-bootstrap dat ook zou doen, maar hier AOT vertaald naar een
     * ketting van test-blokken (hetzelfde idee als {@link #buildCmpBlocks}
     * voor tableswitch/lookupswitch).
     *
     * <p>Alleen klasse-labels (type-patterns, bv. {@code case String s})
     * en String-constante-labels (bv. {@code case "foo"}) worden
     * ondersteund - de twee vormen die in de praktijk voorkomen; andere
     * constante-labelsoorten (geboxte getallen, enum-{@code EnumDesc}'s via
     * geneste {@code ConstantBootstraps})  geven een duidelijke, scoped
     * fout in plaats van een verkeerde match.
     */
    private void emitTypeSwitchBootstrap(final InvokeDynamicInsnNode insn) {
        final IRValue startIndex = stack.pop();
        final IRValue target = stack.pop();

        final Object[] labels = insn.bsmArgs;
        final int n = labels.length;

        final List<IRBasicBlock> checkBlocks = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            checkBlocks.add(new IRBasicBlock(uniqueBlockName()));
        }
        final var noMatchBlk = new IRBasicBlock(uniqueBlockName());
        final var mergeBlk = new IRBasicBlock(uniqueBlockName());
        for (final var blk : checkBlocks) {
            builder.currentFunction().addBlock(blk);
        }
        builder.currentFunction().addBlock(noMatchBlk);
        builder.currentFunction().addBlock(mergeBlk);

        final var cell = builder.emitAlloca("swb." + uniqueBlockName(), IRType.I32);

        builder.emitBranch(n == 0 ? noMatchBlk : checkBlocks.get(0));

        for (int i = 0; i < n; i++) {
            builder.setCurrentBlock(checkBlocks.get(i));
            final IRBasicBlock nextBlk = (i + 1 < n) ? checkBlocks.get(i + 1) : noMatchBlk;

            // Labels vóór startIndex overslaan zonder ze te testen - nodig
            // om een switch te kunnen hervatten na een niet-matchende
            // guarded pattern (de normale eerste aanroep gebruikt
            // startIndex=0, dus dan matcht deze skip-check nooit).
            final IRValue skipCond = builder.emitBinaryOp(
                    IRInstruction.BinaryOp.Op.LT, IRValue.ofI32(i), startIndex);
            final var testBlk = new IRBasicBlock(uniqueBlockName());
            builder.currentFunction().addBlock(testBlk);
            builder.emitCondBranch(skipCond, nextBlk, testBlk);

            builder.setCurrentBlock(testBlk);
            final IRValue matchCond = emitTypeSwitchLabelMatch(target, labels[i]);
            final var foundBlk = new IRBasicBlock(uniqueBlockName());
            builder.currentFunction().addBlock(foundBlk);
            builder.emitCondBranch(matchCond, foundBlk, nextBlk);

            builder.setCurrentBlock(foundBlk);
            builder.emitStore(cell, IRValue.ofI32(i));
            builder.emitBranch(mergeBlk);
        }

        builder.setCurrentBlock(noMatchBlk);
        builder.emitStore(cell, IRValue.ofI32(n));
        builder.emitBranch(mergeBlk);

        builder.setCurrentBlock(mergeBlk);
        stack.push(builder.emitLoad(cell));
    }

    private IRValue emitTypeSwitchLabelMatch(final IRValue target, final Object label) {
        if (label instanceof Type typeConst) {
            noteReference(typeConst.getInternalName());
            final var ptrType = new IRType.Ptr(IRType.I8, typeConst.getDescriptor());
            return builder.emitInstanceOf(target, ptrType);
        }
        if (label instanceof String stringConst) {
            noteMethodReference("java/lang/String", "equals", "(Ljava/lang/Object;)Z", true);
            return builder.emitCall(CallKind.VIRTUAL, "java.lang.String_equals", IRType.BOOL,
                    List.of(OBJECT_PARAM), List.of(target, builder.constString(stringConst)));
        }
        throw new UnsupportedOperationException(
                "SwitchBootstraps.typeSwitch with a label of type " + label.getClass().getName()
                        + " is not supported yet (only class and String labels are supported): "
                        + ownerQualifiedName + "." + methodNode.name);
    }

    /**
     * Elementtype voor een array-descriptor (bv. {@code "[B"} -> {@code i8},
     * {@code "[Ljava/lang/String;"} -> een object-Ptr) - gebruikt door
     * {@link #arrayHashCode}/{@link #arrayEquals} om {@code
     * emitArrayLoad}/{@code emitArrayLength} op de juiste manier aan te
     * roepen, analoog aan {@link #arrayLoad} die dit voor echte AALOAD/
     * BALOAD/...-opcodes doet.
     */
    private IRType arrayElementIrType(final String arrayDescriptor) {
        final char c = arrayDescriptor.charAt(1);
        return switch (c) {
            case 'B' -> IRType.I8;
            case 'C' -> IRType.CHAR;
            case 'S' -> IRType.I16;
            case 'I' -> IRType.I32;
            case 'J' -> IRType.I64;
            case 'F' -> IRType.F32;
            case 'D' -> IRType.F64;
            case 'Z' -> IRType.BOOL;
            case 'L', '[' -> new IRType.Ptr(IRType.I8, arrayDescriptor.substring(1));
            default -> throw new UnsupportedOperationException(
                    "ObjectMethods.bootstrap for array element type '" + c + "' is not supported yet: "
                            + ownerQualifiedName + "." + methodNode.name);
        };
    }

    /**
     * {@code Arrays.hashCode(array)}-semantiek voor een array-veld van een
     * record: records combineren hun velden via de hashCode() VAN DAT VELD,
     * maar een array overschrijft {@code Object.hashCode()} niet (zou dus
     * identity-hash geven, wat twee inhoudelijk gelijke records een
     * verschillende hashCode zou opleveren) - vandaar deze speciale,
     * inhoudelijke array-hashCode, net zoals de echte {@code
     * java.lang.runtime.ObjectMethods}-implementatie array-velden ook
     * special-cased.
     */
    private IRValue arrayHashCode(final IRValue array, final String arrayDescriptor) {
        final IRType elemType = arrayElementIrType(arrayDescriptor);
        final var arrayPtrType = (IRType.Ptr) array.type();

        final var nullBlk = new IRBasicBlock(uniqueBlockName());
        final var notNullBlk = new IRBasicBlock(uniqueBlockName());
        final var mergeBlk = new IRBasicBlock(uniqueBlockName());
        for (final var blk : List.of(nullBlk, notNullBlk, mergeBlk)) {
            builder.currentFunction().addBlock(blk);
        }
        final var resultCell = builder.emitAlloca("arrhash." + uniqueBlockName(), IRType.I32);

        final var isNull = builder.emitBinaryOp(IRInstruction.BinaryOp.Op.EQ, array,
                IRValue.nullPtr(arrayPtrType.pointee()));
        builder.emitCondBranch(isNull, nullBlk, notNullBlk);

        builder.setCurrentBlock(nullBlk);
        builder.emitStore(resultCell, IRValue.ofI32(0));
        builder.emitBranch(mergeBlk);

        builder.setCurrentBlock(notNullBlk);
        final var length = builder.emitArrayLength(array);
        final var hashCell = builder.emitAlloca("arrhash.h." + uniqueBlockName(), IRType.I32);
        builder.emitStore(hashCell, IRValue.ofI32(1));
        final var idxCell = builder.emitAlloca("arrhash.i." + uniqueBlockName(), IRType.I32);
        builder.emitStore(idxCell, IRValue.ofI32(0));

        final var condBlk = new IRBasicBlock(uniqueBlockName());
        final var bodyBlk = new IRBasicBlock(uniqueBlockName());
        final var exitBlk = new IRBasicBlock(uniqueBlockName());
        for (final var blk : List.of(condBlk, bodyBlk, exitBlk)) {
            builder.currentFunction().addBlock(blk);
        }
        builder.emitBranch(condBlk);

        builder.setCurrentBlock(condBlk);
        final var cond = builder.emitBinaryOp(IRInstruction.BinaryOp.Op.LT, builder.emitLoad(idxCell), length);
        builder.emitCondBranch(cond, bodyBlk, exitBlk);

        builder.setCurrentBlock(bodyBlk);
        final var elem = builder.emitArrayLoad(array, builder.emitLoad(idxCell), elemType);
        final var elemHash = fieldHashCode(elem);
        final var scaled = builder.emitBinaryOp(
                IRInstruction.BinaryOp.Op.MUL, builder.emitLoad(hashCell), IRValue.ofI32(31));
        builder.emitStore(hashCell, builder.emitBinaryOp(IRInstruction.BinaryOp.Op.ADD, scaled, elemHash));
        builder.emitStore(idxCell, builder.emitBinaryOp(
                IRInstruction.BinaryOp.Op.ADD, builder.emitLoad(idxCell), IRValue.ofI32(1)));
        builder.emitBranch(condBlk);

        builder.setCurrentBlock(exitBlk);
        builder.emitStore(resultCell, builder.emitLoad(hashCell));
        builder.emitBranch(mergeBlk);

        builder.setCurrentBlock(mergeBlk);
        return builder.emitLoad(resultCell);
    }

    /**
     * {@code Arrays.equals(a, b)}-semantiek (zelfde referentie, of beide
     * non-null met gelijke lengte en element-voor-element gelijke
     * elementen) - de inhoudelijke tegenhanger van {@link #arrayHashCode}.
     */
    private IRValue arrayEquals(final IRValue a, final IRValue b, final String arrayDescriptor) {
        final IRType elemType = arrayElementIrType(arrayDescriptor);
        final var aPtrType = (IRType.Ptr) a.type();

        final var cell = builder.emitAlloca("arreq." + uniqueBlockName(), IRType.BOOL);
        final var sameRefBlk = new IRBasicBlock(uniqueBlockName());
        final var checkNullBlk = new IRBasicBlock(uniqueBlockName());
        final var checkLenBlk = new IRBasicBlock(uniqueBlockName());
        final var loopInitBlk = new IRBasicBlock(uniqueBlockName());
        final var condBlk = new IRBasicBlock(uniqueBlockName());
        final var bodyBlk = new IRBasicBlock(uniqueBlockName());
        final var trueBlk = new IRBasicBlock(uniqueBlockName());
        final var falseBlk = new IRBasicBlock(uniqueBlockName());
        final var mergeBlk = new IRBasicBlock(uniqueBlockName());
        for (final var blk : List.of(sameRefBlk, checkNullBlk, checkLenBlk, loopInitBlk,
                condBlk, bodyBlk, trueBlk, falseBlk, mergeBlk)) {
            builder.currentFunction().addBlock(blk);
        }

        final var refEq = builder.emitBinaryOp(IRInstruction.BinaryOp.Op.EQ, a, b);
        builder.emitCondBranch(refEq, sameRefBlk, checkNullBlk);

        builder.setCurrentBlock(sameRefBlk);
        builder.emitStore(cell, IRValue.ofBool(true));
        builder.emitBranch(mergeBlk);

        builder.setCurrentBlock(checkNullBlk);
        // Geen van beide null (anders was a==b hierboven al true via
        // null==null) maar wel verschillende referenties - alleen gelijk
        // als BEIDE non-null zijn; anders is er precies één null.
        final var aNull = builder.emitBinaryOp(
                IRInstruction.BinaryOp.Op.EQ, a, IRValue.nullPtr(aPtrType.pointee()));
        final var bNull = builder.emitBinaryOp(
                IRInstruction.BinaryOp.Op.EQ, b, IRValue.nullPtr(aPtrType.pointee()));
        final var eitherNull = builder.emitBinaryOp(IRInstruction.BinaryOp.Op.OR, aNull, bNull);
        builder.emitCondBranch(eitherNull, falseBlk, checkLenBlk);

        builder.setCurrentBlock(checkLenBlk);
        final var lenA = builder.emitArrayLength(a);
        final var lenB = builder.emitArrayLength(b);
        final var lenEq = builder.emitBinaryOp(IRInstruction.BinaryOp.Op.EQ, lenA, lenB);
        builder.emitCondBranch(lenEq, loopInitBlk, falseBlk);

        builder.setCurrentBlock(loopInitBlk);
        final var idxCell = builder.emitAlloca("arreq.i." + uniqueBlockName(), IRType.I32);
        builder.emitStore(idxCell, IRValue.ofI32(0));
        builder.emitBranch(condBlk);

        builder.setCurrentBlock(condBlk);
        final var hasNext = builder.emitBinaryOp(IRInstruction.BinaryOp.Op.LT, builder.emitLoad(idxCell), lenA);
        builder.emitCondBranch(hasNext, bodyBlk, trueBlk);

        builder.setCurrentBlock(bodyBlk);
        final var ea = builder.emitArrayLoad(a, builder.emitLoad(idxCell), elemType);
        final var eb = builder.emitArrayLoad(b, builder.emitLoad(idxCell), elemType);
        final var elemEq = fieldEquals(ea, eb);
        final var nextBlk = new IRBasicBlock(uniqueBlockName());
        builder.currentFunction().addBlock(nextBlk);
        builder.emitCondBranch(elemEq, nextBlk, falseBlk);

        builder.setCurrentBlock(nextBlk);
        builder.emitStore(idxCell, builder.emitBinaryOp(
                IRInstruction.BinaryOp.Op.ADD, builder.emitLoad(idxCell), IRValue.ofI32(1)));
        builder.emitBranch(condBlk);

        builder.setCurrentBlock(trueBlk);
        builder.emitStore(cell, IRValue.ofBool(true));
        builder.emitBranch(mergeBlk);

        builder.setCurrentBlock(falseBlk);
        builder.emitStore(cell, IRValue.ofBool(false));
        builder.emitBranch(mergeBlk);

        builder.setCurrentBlock(mergeBlk);
        return builder.emitLoad(cell);
    }
}

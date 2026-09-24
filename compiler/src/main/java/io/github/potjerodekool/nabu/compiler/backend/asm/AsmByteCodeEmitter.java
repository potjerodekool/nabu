package io.github.potjerodekool.nabu.compiler.backend.asm;

import io.github.potjerodekool.nabu.backend.CompileOptions;
import io.github.potjerodekool.nabu.backend.ir.PhiElimination;
import io.github.potjerodekool.nabu.backend.jvm.BytecodeHelper;
import io.github.potjerodekool.nabu.backend.jvm.Linearizer;
import io.github.potjerodekool.nabu.backend.jvm.SlotAllocator;
import io.github.potjerodekool.nabu.backend.ir.IRBasicBlock;
import io.github.potjerodekool.nabu.backend.ir.IRField;
import io.github.potjerodekool.nabu.backend.ir.IRFunction;
import io.github.potjerodekool.nabu.backend.ir.IRGlobal;
import io.github.potjerodekool.nabu.backend.ir.IRModule;
import io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;
import io.github.potjerodekool.nabu.lang.Flags;
import io.github.potjerodekool.nabu.lang.model.element.*;
import io.github.potjerodekool.nabu.compiler.resolve.method.jvm.AccessUtils;
import io.github.potjerodekool.nabu.type.DeclaredType;
import io.github.potjerodekool.nabu.type.TypeMirror;
import org.objectweb.asm.*;
import org.objectweb.asm.util.TraceClassVisitor;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Emitteert een heel {@link IRModule} naar een Java-klassebestand.
 *
 * Elke functie wordt eerst door {@link PhiElimination} gehaald (phi's worden
 * vervangen door stores in predecessor-blokken en loads in de header-blokken),
 * daarna geordend door {@link Linearizer} en vervolgens emissie via
 * {@link FunctionEmitter} met een per-functie {@link SlotAllocator}.
 */
public class AsmByteCodeEmitter {

    private final ClassWriter cw;
    private final ClassVisitor classVisitor;
    private final Map<String, IRGlobal> globalMap = new HashMap<>();
    private String ownerInternalName;

    public AsmByteCodeEmitter() {
        cw = new ClassWriter(
                ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES
        ) {
            @Override
            protected String getCommonSuperClass(final String type1, final String type2) {
                try {
                    return super.getCommonSuperClass(type1, type2);
                } catch (final RuntimeException e) {
                    return "java/lang/Object";
                }
            }
        };
        this.classVisitor = cw;
    }

    public IRGlobal getGlobal(final String name) {
        return globalMap.get(name);
    }

    private int resolveModuleAccess(final IRModule module) {
        int access;

        if (module.flags == 0) {
            access = Opcodes.ACC_PUBLIC + Opcodes.ACC_SUPER;
        } else {
            if (Flags.hasFlag(module.flags, Flags.INTERFACE)) {
                access = Opcodes.ACC_PUBLIC + Opcodes.ACC_ABSTRACT + Opcodes.ACC_INTERFACE;
            } else {
                access = Opcodes.ACC_SUPER;

                if (Flags.hasFlag(module.flags, Flags.PUBLIC)) {
                    access += Opcodes.ACC_PUBLIC;
                }
                if (Flags.hasFlag(module.flags, Flags.FINAL)) {
                    access += Opcodes.ACC_FINAL;
                }
                if (Flags.hasFlag(module.flags, Flags.ABSTRACT)) {
                    access += Opcodes.ACC_ABSTRACT;
                }
                if (Flags.hasFlag(module.flags, Flags.RECORD)) {
                    access += Opcodes.ACC_RECORD;
                }
                if (Flags.hasFlag(module.flags, Flags.ENUM)) {
                    access += Opcodes.ACC_ENUM;
                }
            }
        }

        return access;
    }

    public void emit(final IRModule module,
                     final CompileOptions compileOptions) {
        module.globals().forEach(this::emitGlobal);

        final var javaVersion = compileOptions.javaVersion();
        final var classVersion = javaVersion.getValue();
        final var access = resolveModuleAccess(module);
        final var internalName = BytecodeHelper.toInternalName(module.name);
        this.ownerInternalName = internalName;
        final String signature = module.genericSignature();
        final var superName = module.superType() != null
                ? BytecodeHelper.toInternalName(module.superType())
                : "java/lang/Object";

        final var interfaces = module.interfaces().stream()
                .map(BytecodeHelper::toInternalName)
                .toArray(String[]::new);

        final var rawSourceFile = module.sourceFile();
        final var fileName = rawSourceFile.contains("/") || rawSourceFile.contains("\\")
                ? Path.of(rawSourceFile).getFileName().toString()
                : rawSourceFile;

        classVisitor.visit(classVersion, access, internalName, signature, superName, interfaces);
        classVisitor.visitSource(fileName, null);

        // Emit PermittedSubclasses attribute for sealed classes
        if (module.sealedClass()) {
            final var permittedSubclasses = module.permittedSubclasses();
            if (!permittedSubclasses.isEmpty()) {
                for (final var subclass : permittedSubclasses) {
                    classVisitor.visitPermittedSubclass(BytecodeHelper.toInternalName(subclass));
                }
            }
        }

        emitAnnotations(module.annotations(), classVisitor::visitAnnotation);

        module.fields().forEach(this::emitField);

        for (final var function : module.functions()) {
            activeReturnIRType = function.returnType;
            emitFunction(function);
        }

        try {
            classVisitor.visitEnd();
        } catch (final Exception e) {
            System.err.println("[AsmByteCodeEmitter] Error finalizing class: " + module.name);
            throw e;
        }
    }

    private void emitGlobal(final String name,
                            final IRGlobal global) {
        this.globalMap.put(name, global);
    }

    private void emitFunction(final IRFunction function) {
        if (function.isExternal()) {
            return;
        }

        final var access = AccessUtils.flagsToAccess(function.getFlags());
        final var isStatic = Flags.hasFlag(function.getFlags(), Flags.STATIC);

        var name = function.name;
        final var separatorIndex = name.indexOf('_');
        if (separatorIndex > -1) {
            name = name.substring(separatorIndex + 1);
        }

        if (function.isConstructor()) {
            name = "<init>";
        }

        var params = function.params;

        if (!isStatic && !params.isEmpty()) {
            if (params.size() == 1) {
                params = List.of();
            } else {
                params = params.subList(1, params.size());
            }
        }

        final var descriptor = BytecodeHelper.createDescriptorWithValues(
                params,
                function.returnType
        );

        var methodVisitor = classVisitor.visitMethod(
                access,
                name,
                descriptor,
                function.genericSignature(),
                null
        );

        emitAnnotations(function.annotations(), methodVisitor::visitAnnotation);

        // Parameter-annotations emit
        final var paramAnns = function.parameterAnnotations();
        int annOffset = isStatic ? 0 : 1;
        for (int i = 0; i < params.size(); i++) {
            if (annOffset + i < paramAnns.size()) {
                final var paramAnnList = paramAnns.get(annOffset + i);
                for (final var ann : paramAnnList) {
                    final var annType = ann.getAnnotationType();
                    if (annType == null) continue;
                    final var desc = BytecodeHelper.createDescriptor(annType);
                    final var av = methodVisitor.visitParameterAnnotation(i, desc, true);
                    if (av != null) {
                        emitAnnotationValues(av, ann);
                        av.visitEnd();
                    }
                }
            }
        }

        for (final var parameter : params) {
            if (parameter instanceof IRValue.Temp temp) {
                methodVisitor.visitParameter(
                        SlotAllocator.normalize(temp.name()),
                        Opcodes.ACC_FINAL
                );
            }
        }

        if (!function.blocks().isEmpty()) {
            methodVisitor.visitCode();

            final var slots = new SlotAllocator();
            allocateParams(slots, function, isStatic);

            PhiElimination.run(function);

            // VÃ³Ã³r de emissie worden ALLE slots toegewezen (zodat er geen slots
            // meer luierg tijdens de emissie bijkomen), en elk basisblok start met
            // een proloog van dummy-stores. Hierdoor heeft elk basisblok (en elke
            // door ASM gemaakte xSTORE-split) dezelfde local-variable-count en
            // opbouw. Dat is nodig omdat ASM's exception-handler-frame berekend
            // wordt als de merge van de input-frames van ALLE blokken in de
            // try-range: bij ongelijke local-counts loopt Frame.merge spaak
            // ("Index out of bounds for length ...").
            final var slotTypes = new HashMap<Integer, IRType>();
            for (final var block : function.blocks()) {
                for (final var instr : block.instructions()) {
                    collectSlotUses(instr, slots, slotTypes);
                }
            }
            final var handlerEntrySlots = new HashMap<String, String>();
            for (final var block : function.blocks()) {
                for (final var instr : block.instructions()) {
                    if (instr instanceof IRInstruction.TryCatchRegion tc) {
                        handlerEntrySlots.put(tc.handlerLabel(), "exn." + tc.handlerLabel());
                        final var exnType = new IRType.Ptr(IRType.I8, null);
                        final var slot = slots.slotOf("exn." + tc.handlerLabel(), exnType);
                        slotTypes.putIfAbsent(slot, exnType);
                    }
                }
            }

            // Phi-carried slots overslaan in de proloog: de waarde van een
            // phi-temp (na PhiElimination een Store in elke predecessor)
            // wordt bij de blok-entry leeggemaakt door de proloog-dumSTORE,
            // waardoor loop-carried waarden verloren gaan (oneindige lussen).
            final var phiSkipSlots = new java.util.HashSet<Integer>();
            for (final var block : function.blocks()) {
                for (final var instr : block.instructions()) {
                    if (instr instanceof IRInstruction.Store store
                            && store.ptr() instanceof IRValue.Temp temp
                            && temp.name().startsWith("%phi")) {
                        phiSkipSlots.add(slots.slotOf(temp.name(), temp.type()));
                    }
                }
            }

            // Phi-slots worden NIET meer geskipt in de proloog: de phi-temp
            // (na PhiElimination een Store in elke predecessor) wordt vÃ³Ã³r de
            // branch altijd gevolgd door een echte Store, zodat een
            // proloog-default (0/null) op ieder basisblok veilig is en de
            // exception-edge altijd een gedefinieerd frame heeft.
            // Phi-slots worden NIET meer geskipt in de proloog: de phi-temp
            // (na PhiElimination een Store in elke predecessor) wordt vÃ³Ã³r de
            // branch altijd gevolgd door een echte Store, zodat een
            // proloog-default (0/null) op ieder basisblok veilig is en de
            // exception-edge altijd een gedefinieerd frame heeft.
            var blocks = pruneUnreachable(Linearizer.linearize(function.blocks()));
            final var prologueSkipByBlock = new java.util.HashMap<IRBasicBlock, java.util.Set<Integer>>();
            PROLOGUE_LIVE_IN_BY_BLOCK.clear();
            for (final var block : blocks) {
                final var liveIn = collectLiveInSlots(block, slots, paramSlotCount(function, isStatic));
                prologueSkipByBlock.put(
                        block,
                        liveIn);
                PROLOGUE_LIVE_IN_BY_BLOCK.put(block, liveIn);
            }
            final var emitter = new FunctionEmitter(methodVisitor, this, slots, ownerInternalName, blocks);
            activeFunctionEmitter = emitter;

            // TryCatchRegion-planning: per try-bereik worden de beschermde blokken
            // (bereikbaar vanuit de try-start, exclusief handler-blokken en andere
            // try-starts) bepaald; elke aaneengesloten reeks wordt als aparte
            // exception-table range geregistreerd, met een end-label na het laatste
            // blok van de reeks.
            final var runEndLabels = new HashMap<String, List<Label>>();
            planTryRegions(blocks, emitter, methodVisitor, runEndLabels);

            // Elke fall-through edge in een handler-entry blok moet EXPLICIET
            // worden: een implicite val-through (normaal, met een LEEG stack)
            // naar het entry-label van een catch-handler resulteert in een
            // ongeldige frame-merge (lege stack vs. de JVM-caught exception
            // die de catch-edge pusht). ASM's COMPUTE_ALL_FRAMES loopt daar
            // vast: NegativeArraySizeException (-1) / ArrayIndexOutOfBounds(-1)
            // in Frame.merge/getConcreteOutputType. Oplossing: per handler-entry
            // krijgt een body-fallback label dat nÃ¡ de 'astore exn' wordt bezet;
            // een voorgaand blok zonder terminator springt expliciet GOTO daarin.
            final java.util.Map<String, Label> fallBodyByHandler = new HashMap<>();
            for (final var b : blocks) {
                if (handlerEntrySlots.containsKey(b.label())) {
                    fallBodyByHandler.put(b.label(),
                            emitter.newLabelFor("fallbody[" + b.label() + "]"));
                }
            }
            activeFallBodyByHandler = fallBodyByHandler;

            try {
                var prevEndsWithFallthrough = false;
                Label pendingFallBody = null;
                for (final var block : blocks) {
                    final var handlerSlotName = handlerEntrySlots.get(block.label());
                    final boolean isHandler = handlerSlotName != null;

                    // Expliciete sprong: een voorgaand blok zonder terminator
                    // valt impliciet door naar de entry-label van het
                    // volgende blok. Mag NIET met een handler-entry gebeuren:
                    // stuur dat pad expliciet naar het body-fallback label.
                    if (prevEndsWithFallthrough && isHandler) {
                        methodVisitor.visitJumpInsn(Opcodes.GOTO, pendingFallBody);
                        pendingFallBody = null;
                    }
                    prevEndsWithFallthrough = false;

                    emitter.emitBlock(block);

                    // Proloog: definieer alle niet-parameter slots als dummy-waarde,
                    // zodat ieder basisblok dezelfde lokale-frame-opbouw heeft.
                    // Phi-carried en live-in slots worden overgeslagen.
                    // In exception-handler blokken wordt NIETS overgeslagen:
                    // de exception-edge kan het blok bereiken zonder dat een
                    // normale voorganger de phi/live-in waarden heeft gezet
                    // ('Expected I, but found .' in de verifier); de handler
                    // krijgt dan bewust defaults (0/null) â€” correcte frames.
                    // [Prologue-slimming] Slots die dit blok NIET aanraakt
                    // (naam komt nergens in de blok-instructies voor) krijgen
                    // geen dummy-store: pure bytecode-overhead die grote picocli-
                    // methoden boven de JVM-limiet (MethodTooLargeException)
                    // jaagt. Voor het frame-model is dit veilig: het blok leest
                    // of schrijft die lokale nergens; een evt. TOP-merge op dat
                    // lokale wordt pas relevant in latere blokken, en die
                    // definiÃ«ren het lokale zelfhandig in hun eigen proloog.
                    emitPrologue(methodVisitor, slots, slotTypes,
                            paramSlotCount(function, isStatic),
                            computePrologSkip(block, slots, slotTypes, isHandler,
                                    paramSlotCount(function, isStatic)));

                    // Handler-entry: de JVM duwt de exception op de stack;
                    // sla die op in het %exn.<handlerLabel>-slot dat de IR laadt,
                    // zodat de stack netjes leeg is voor de blok-instructies.
                    if (handlerSlotName != null) {
                        methodVisitor.visitVarInsn(Opcodes.ASTORE, slots.getSlot(handlerSlotName));
                        // Body-fallback: het normale (fall-through) pad landt hier,
                        // nÃ¡ de exception-astore, zodat de catch-edge de enige
                        // in-edge van het entry-label blijft.
                        methodVisitor.visitLabel(fallBodyByHandler.get(block.label()));
                        prevEndsWithFallthrough = false;
                    }

                    for (final var instr : block.instructions()) {
                        emitter.emit(instr);
                    }

                    // Een blok dat zonder terminator (Branch/CondBranch/Return/Throw)
                    // eindigt valt impliciet door naar het volgende label. Vlag dit
                    // voor het opvolgende blok; als dat een handler-entry is, wordt
                    // de val expliciet naar het body-fallback label gemaakt.
                    prevEndsWithFallthrough = !endsWithTerminator(block);

                    // Eind-labels van try-runs die in dit blok eindigen.
                    final var ends = runEndLabels.get(block.label());
                    if (ends != null) {
                        for (final var endLabel : ends) {
                            methodVisitor.visitLabel(endLabel);
                        }
                    }
                }
                emitter.visitLabel("END");
                emitter.visitLocalVariables(function, function.blocks().getFirst().label());

                methodVisitor.visitMaxs(-1, -1);
            } catch (final Exception e) {
                System.err.println("[AsmByteCodeEmitter] Error in function: " + function.name);
                e.printStackTrace(System.err);
                throw e;
            }
            methodVisitor.visitEnd();
        } else {
            methodVisitor.visitEnd();
        }
    }

    /**
     * Kent slots toe aan "this" (voor instantie-methoden) en aan alle
     * gedeclareerde parameters, in JVM-volgorde (categorie-2 neemt 2 slots).
     */
    private void allocateParams(final SlotAllocator slots,
                                final IRFunction function,
                                final boolean isStatic) {
        if (!isStatic) {
            final var thisParam = function.params.getFirst();
            slots.allocateParam(SlotAllocator.normalize(IRValue.nameOf(thisParam)), thisParam.type());
        }

        var params = function.params;
        if (!isStatic && !params.isEmpty()) {
            params = params.size() == 1 ? List.of() : params.subList(1, params.size());
        }

        for (final var param : params) {
            slots.allocateParam(SlotAllocator.normalize(IRValue.nameOf(param)), param.type());
        }
    }

    private void emitField(final IRField field) {
        final var access = AccessUtils.flagsToAccess(field.flags());
        final var name = field.name();
        final var descriptor = BytecodeHelper.createDescriptor(field.type());

        switch (field.kind()) {
            case RECORD_COMPONENT -> {
                final var recordVisitor = classVisitor.visitRecordComponent(
                        name,
                        descriptor,
                        field.genericSignature()
                );
                recordVisitor.visitEnd();
            }
            case FIELD -> {
                final var fieldVisitor = classVisitor.visitField(
                        access,
                        field.name(),
                        descriptor,
                        field.genericSignature(),
                        null
                );
                emitAnnotations(field.annotations(), fieldVisitor::visitAnnotation);
                fieldVisitor.visitEnd();
            }
        }
    }

    private void emitAnnotations(final List<CompoundAttribute> annotations,
                                 final BiFunction<String, Boolean, AnnotationVisitor> visitorFactory) {
        for (final var annotation : annotations) {
            final var annotationType = annotation.getAnnotationType();
            if (annotationType == null) continue;

            final var descriptor = BytecodeHelper.createDescriptor(annotationType);
            final var av = visitorFactory.apply(descriptor, true);
            if (av == null) continue;

            emitAnnotationValues(av, annotation);
            av.visitEnd();
        }
    }

    private void emitAnnotationValues(final AnnotationVisitor av,
                                      final CompoundAttribute annotation) {
        for (final var entry : annotation.getElementValues().entrySet()) {
            final var methodName = entry.getKey().getSimpleName();
            final var value = entry.getValue();
            emitAnnotationValue(av, methodName, value);
        }
    }

    private void emitAnnotationValue(final AnnotationVisitor av,
                                     final String name,
                                     final AnnotationValue value) {
        if (value instanceof ConstantAttribute constant) {
            final var raw = constant.getValue();
            if (raw instanceof Boolean b) av.visit(name, b);
            else if (raw instanceof Byte b) av.visit(name, b);
            else if (raw instanceof Character c) av.visit(name, c);
            else if (raw instanceof Double d) av.visit(name, d);
            else if (raw instanceof Float f) av.visit(name, f);
            else if (raw instanceof Integer i) av.visit(name, i);
            else if (raw instanceof Long l) av.visit(name, l);
            else if (raw instanceof Short s) av.visit(name, s);
            else if (raw instanceof String s) av.visit(name, s);
        } else if (value instanceof EnumAttribute enumAttr) {
            final var varElement = enumAttr.getValue();
            final var enumType = enumAttr.getType();
            final var enumDesc = BytecodeHelper.createDescriptor(enumType);
            av.visitEnum(name, enumDesc, varElement.getSimpleName());
        } else if (value instanceof CompoundAttribute nested) {
            final var nestedType = nested.getAnnotationType();
            if (nestedType != null) {
                final var nestedDesc = BytecodeHelper.createDescriptor(nestedType);
                final var nav = av.visitAnnotation(name, nestedDesc);
                if (nav != null) {
                    emitAnnotationValues(nav, nested);
                    nav.visitEnd();
                }
            }
        } else if (value instanceof ArrayAttribute arrayAttr) {
            final var aav = av.visitArray(name);
            if (aav != null) {
                for (final var elem : arrayAttr.getValue()) {
                    emitAnnotationValue(aav, null, elem);
                }
                aav.visitEnd();
            }
        } else if (value instanceof ClassAttribute classAttr) {
            final var typeMirror = classAttr.getValue();
            if (typeMirror instanceof DeclaredType dt
                    && "java.lang.Class".equals(((TypeElement) dt.asElement()).getQualifiedName())
                    && !dt.getTypeArguments().isEmpty()) {
                final var typeArg = dt.getTypeArguments().getFirst();
                final var typeDesc = BytecodeHelper.createDescriptor(typeArg);
                av.visit(name, Type.getType(typeDesc));
            } else if (typeMirror instanceof TypeMirror tm) {
                final var typeDesc = BytecodeHelper.createDescriptor(tm);
                av.visit(name, Type.getType(typeDesc));
            }
        }
    }

    /**
     * Kent een slot toe aan een SSA-temp (en registreert het type), zodat alle
     * slots vÃ³Ã³r de emissie vastliggen.
     */
    private static boolean isReferenceKind(final IRValue value) {
        if (value == null || value instanceof IRValue.Temp) {
            return false;
        }
        return switch (value) {
            case IRValue.ConstNull ignored -> true;
            case IRValue.ConstString ignored -> true;
            case IRValue.ConstClass ignored -> true;
            case IRValue.FunctionRef ignored -> true;
            default -> value.type() instanceof IRType.Ptr || value.type() instanceof IRType.Array;
        };
    }

    private static void collectValue(final IRValue value,
                                     final SlotAllocator slots,
                                     final Map<Integer, IRType> slotTypes) {
        if (value instanceof IRValue.Temp temp) {
            final var slot = slots.slotOf(temp.name(), temp.type());
            slotTypes.putIfAbsent(slot, temp.type());
        }
    }

    /**
     * Doorloopt alle IRValue-operanden van een instructie en kent aan elke
     * SSA-temp een slot toe.
     */
    private static void collectSlotUses(final IRInstruction instr,
                                        final SlotAllocator slots,
                                        final Map<Integer, IRType> slotTypes) {
        switch (instr) {
            case IRInstruction.BinaryOp binaryOp -> {
                collectValue(binaryOp.result(), slots, slotTypes);
                collectValue(binaryOp.left(), slots, slotTypes);
                collectValue(binaryOp.right(), slots, slotTypes);
            }
            case IRInstruction.Alloca alloca -> {
                if (alloca.result() instanceof IRValue.Temp temp) {
                    // De slot-type voor een variabele-alloca is het element-type
                    // (allocType), NIET het pointer-type van de ssa-temp: de
                    // load/store-instructies adresseren dit slot met echte
                    // waarden (bv. ICONST_0;ISTORE 4 voor een i32-variabele).
                    // Temp.type() is altijd Ptr en zou de handler-proloog
                    // ACONST_NULL/ASTORE (R) laten schrijven waardoor ILOAD
                    // ongeldig wordt ('Expected I, but found R').
                    final var slot = slots.slotOf(temp.name(), alloca.allocType());
                    slotTypes.putIfAbsent(slot, alloca.allocType());
                }
            }
            case IRInstruction.ArrayStore arrayStore -> {
                collectValue(arrayStore.array(), slots, slotTypes);
                collectValue(arrayStore.index(), slots, slotTypes);
                collectValue(arrayStore.value(), slots, slotTypes);
            }
            case IRInstruction.ArrayLoad arrayLoad -> {
                collectValue(arrayLoad.result(), slots, slotTypes);
                collectValue(arrayLoad.array(), slots, slotTypes);
                collectValue(arrayLoad.index(), slots, slotTypes);
            }
            case IRInstruction.ArrayLength arrayLength -> {
                collectValue(arrayLength.result(), slots, slotTypes);
                collectValue(arrayLength.array(), slots, slotTypes);
            }
            case IRInstruction.Load load -> {
                collectValue(load.result(), slots, slotTypes);
                collectValue(load.ptr(), slots, slotTypes);
            }
            case IRInstruction.Store store -> {
                collectValue(store.ptr(), slots, slotTypes);
                collectValue(store.value(), slots, slotTypes);
                if (store.ptr() instanceof IRValue.Temp temp
                        && isReferenceKind(store.value())) {
                    slotTypes.put(slots.slotOf(temp.name(), temp.type()), store.value().type());
                }
            }
            case IRInstruction.HeapAlloc heapAlloc -> collectValue(heapAlloc.result(), slots, slotTypes);
            case IRInstruction.AllocaArray allocaArray -> {
                collectValue(allocaArray.result(), slots, slotTypes);
                collectValue(allocaArray.size(), slots, slotTypes);
            }
            case IRInstruction.Call call -> {
                collectValue(call.result(), slots, slotTypes);
                call.args().forEach(arg -> collectValue(arg, slots, slotTypes));
            }
            case IRInstruction.IndirectCall indirectCall -> {
                collectValue(indirectCall.result(), slots, slotTypes);
                collectValue(indirectCall.callee(), slots, slotTypes);
                indirectCall.args().forEach(arg -> collectValue(arg, slots, slotTypes));
            }
            case IRInstruction.CondBranch condBranch -> collectValue(condBranch.condition(), slots, slotTypes);
            case IRInstruction.Return returnInst -> collectValue(returnInst.value(), slots, slotTypes);
            case IRInstruction.Cast cast -> {
                collectValue(cast.result(), slots, slotTypes);
                collectValue(cast.source(), slots, slotTypes);
            }
            case IRInstruction.InstanceOf instanceOf -> {
                collectValue(instanceOf.result(), slots, slotTypes);
                collectValue(instanceOf.source(), slots, slotTypes);
            }
            case IRInstruction.MonitorEnter monitorEnter -> collectValue(monitorEnter.object(), slots, slotTypes);
            case IRInstruction.MonitorExit monitorExit -> collectValue(monitorExit.object(), slots, slotTypes);
            case IRInstruction.Throw throwInst -> collectValue(throwInst.result(), slots, slotTypes);
            case IRInstruction.Pop pop -> collectValue(pop.result(), slots, slotTypes);
            case IRInstruction.Move move -> {
                collectValue(move.result(), slots, slotTypes);
                collectValue(move.value(), slots, slotTypes);
            }
            case IRInstruction.Phi phi -> {
                collectValue(phi.result(), slots, slotTypes);
                phi.incomingValues().forEach(incoming -> collectValue(incoming.value(), slots, slotTypes));
            }
            case IRInstruction.Branch br -> {
            }
            case IRInstruction.TryCatchRegion regionOnly -> {
            }
        }
    }

    /**
     * Aantal JVM-slots dat de parameters van de functie innemen (slot 0 = "this"
     * voor instantie-methoden).
     */
    private static int paramSlotCount(final IRFunction function, final boolean isStatic) {
        int count = 0;
        if (!isStatic) {
            count += 1;
        }
        final var start = isStatic ? 0 : 1;
        for (int i = start; i < function.params.size(); i++) {
            count += SlotAllocator.slotSize(function.params.get(i).type());
        }
        return count;
    }

    /**
     * Emitteert vÃ³Ã³r de instructies van een basisblok een reeks dummy-stores voor
     * alle niet-parameter slots. Daardoor heeft elk basisblok dezelfde
     * local-frame-opbouw, wat noodzakelijk is voor ASM's frame-berekening bij
     * exception-handlers.
     */
    /**
     * Elders gedefinieerde slots die in een blok gelezen worden vÃ³Ã³r de
     * eerste lokale schrijf: die moeten door de prolog-dummy-stores met
     * rust gelaten worden, anders gaat een loop-carried of cross-block
     * waarde verloren (bv. phi-eliminatie-temps, veld-karbeleketens).
     */
    /**
     * Bepaalt per basisblok welke slots "live-in" zijn: slots die elders
     * gedefinieerd zijn en vÃ³Ã³r de eerste definitie in dit blok gelezen
     * worden. Die slots moet de proloog met rust laten, anders gaat een
     * loop-carried of cross-block waarde verloren (bv. phi-eliminatie-temps).
     * Let op: per blok, NIET als unie over alle blokken. Een globale unie
     * zou ook leegvallen op blokken die het slot zelf niet als live-in
     * hebben (bv. een lege exit-blok), waardoor die blok de proloog het
     * slot niet definieert en een fall-through de verifier-fout
     * 'Expected ... , but found .' oplevert.
     */
    private static java.util.Set<Integer> collectLiveInSlots(final IRBasicBlock block,
                                                             final SlotAllocator slots,
                                                             final int paramSlotCount) {
        final var liveIn = new java.util.HashSet<Integer>();
        final var defined = new java.util.HashSet<Integer>();
        for (int p = 0; p < paramSlotCount; p++) {
            defined.add(p);
        }

        for (final var instr : block.instructions()) {
            for (final var read : readValues(instr)) {
                final var slot = slotIndexOf(read, slots);
                if (slot >= 0 && !defined.contains(slot)) {
                    liveIn.add(slot);
                }
            }
            for (final var write : writtenTemps(instr)) {
                final var slot = slotIndexOf(write, slots);
                if (slot >= 0) {
                    defined.add(slot);
                }
            }
        }

        return liveIn;
    }

    private static List<IRValue> readValues(final IRInstruction instr) {
        final var reads = new java.util.ArrayList<IRValue>();
        switch (instr) {
            case IRInstruction.BinaryOp op -> {
                reads.add(op.left());
                reads.add(op.right());
            }
            case IRInstruction.Load ld -> reads.add(ld.ptr());
            case IRInstruction.Store st -> reads.add(st.value());
            case IRInstruction.Move m -> reads.add(m.value());
            case IRInstruction.Call call -> reads.addAll(call.args());
            case IRInstruction.IndirectCall ic -> {
                reads.add(ic.callee());
                reads.addAll(ic.args());
            }
            case IRInstruction.CondBranch cb -> reads.add(cb.condition());
            case IRInstruction.Return ret -> {
                if (ret.value() != null) reads.add(ret.value());
            }
            case IRInstruction.Cast c -> reads.add(c.source());
            case IRInstruction.InstanceOf i -> reads.add(i.source());
            case IRInstruction.ArrayLoad al -> {
                reads.add(al.array());
                reads.add(al.index());
            }
            case IRInstruction.ArrayStore as -> {
                reads.add(as.array());
                reads.add(as.index());
                reads.add(as.value());
            }
            case IRInstruction.ArrayLength al -> reads.add(al.array());
            case IRInstruction.AllocaArray aa -> reads.add(aa.size());
            case IRInstruction.MonitorEnter me -> reads.add(me.object());
            case IRInstruction.MonitorExit mx -> reads.add(mx.object());
            case IRInstruction.Throw t -> {
                if (t.result() != null) reads.add(t.result());
            }
            default -> {}
        }
        return reads;
    }

    private static List<IRValue> writtenTemps(final IRInstruction instr) {
        final var writes = new java.util.ArrayList<IRValue>();
        switch (instr) {
            case IRInstruction.BinaryOp op -> writes.add(op.result());
            case IRInstruction.Load ld -> writes.add(ld.result());
            case IRInstruction.Alloca al -> writes.add(al.result());
            case IRInstruction.Store st -> writes.add(st.ptr());
            case IRInstruction.Move m -> writes.add(m.result());
            case IRInstruction.Call call -> {
                if (call.result() != null) writes.add(call.result());
            }
            case IRInstruction.IndirectCall ic -> {
                if (ic.result() != null) writes.add(ic.result());
            }
            case IRInstruction.ArrayLoad al -> writes.add(al.result());
            case IRInstruction.ArrayLength al -> writes.add(al.result());
            case IRInstruction.AllocaArray al -> writes.add(al.result());
            case IRInstruction.Cast c -> writes.add(c.result());
            case IRInstruction.InstanceOf i -> writes.add(i.result());
            default -> {}
        }
        return writes;
    }

    private static int slotIndexOf(final IRValue value, final SlotAllocator slots) {
        if (value instanceof IRValue.Temp temp) {
            return slots.slotOf(temp.name(), temp.type());
        }
        return -1;
    }

    /**
     * Bepaalt de skip-set voor de proloog van een blok:
     * - slots die het blok nergens aanraakt (nummer NIET in de IR-tekst)
     *   -> geen dummy-store;
     * - niet-handler: plus de live-in slots (die dragen waarden van
     *   de voorgangers en mogen niet overschreven worden).
     */
    /**
     * [Prologue-slimming vol. 2] Normale blokken krijgen GEEN proloog-dummy-stores:
     * het IR is SSA-getransformeerd - elke SSA-waarde wordt in een voorganger
     * ge-STOORED (phi-eliminatie) vÃ³Ã³r dat dit blok de waarde leest, en de
     * JVM-verifier merged de locals normaal (null/TOP-tolerant). Dit halveert de
     * bytecode-grootte (de MethodTooLargeException bij picocli's grote
     * registerBuiltInConverters). Handler-entry blokken behouden de VOLLEDIGE
     * proloog: de exception-edge trÃ©kkt het blok zonder dat normale voorgangers
     * phi/live-in waarden gezet hebben. N.B.: als een normaal blok een slot
     * leest dat - via een iet-verwante SSA-waarde - door GEEN enkele voorganger
     * werd gezet, vangt de verifier dat met een 'Expected I, but found.'-fout;
     * die indicator is dan laatstedge reductie-bom van deze struik.
     */
    private static java.util.Set<Integer> computePrologSkip(
            final IRBasicBlock block,
            final SlotAllocator slots,
            final Map<Integer, IRType> slotTypes,
            final boolean isHandler,
            final int paramSlotCount) {
        final java.util.HashSet<Integer> skip = new java.util.HashSet<>();
        if (!isHandler) {
            // Terug naar de bewezen-veilige variant: alleen de live-in slots
            // geskipt; alle andere slots krijgen hun proloog-default.
            final var liveIn = PROLOGUE_LIVE_IN_BY_BLOCK.get(block);
            if (liveIn != null) {
                skip.addAll(liveIn);
            }
        }
        return skip;
    }

    private static final Map<IRBasicBlock, java.util.Set<Integer>> PROLOGUE_LIVE_IN_BY_BLOCK =
            new HashMap<>();

    private static void emitPrologue(final MethodVisitor methodVisitor,
                                     final SlotAllocator slots,
                                     final Map<Integer, IRType> slotTypes,
                                     final int paramSlotCount,
                                     final java.util.Set<Integer> skipSlots) {
        final var total = slots.size();
        for (int slot = paramSlotCount; slot < total; slot++) {
            if (skipSlots != null && skipSlots.contains(slot)) {
                continue;
            }
            final var type = slotTypes.get(slot);
            if (type == null) {
                continue;
            }
            if (type instanceof IRType.Void) {
                continue;
            } else if (type instanceof IRType.Int intType && intType.bits() == 64) {
                methodVisitor.visitInsn(Opcodes.LCONST_0);
                methodVisitor.visitVarInsn(Opcodes.LSTORE, slot);
            } else if (type instanceof IRType.Int || type instanceof IRType.Bool) {
                methodVisitor.visitInsn(Opcodes.ICONST_0);
                methodVisitor.visitVarInsn(Opcodes.ISTORE, slot);
            } else if (type instanceof IRType.Float floatType && floatType.bits() == 64) {
                methodVisitor.visitInsn(Opcodes.DCONST_0);
                methodVisitor.visitVarInsn(Opcodes.DSTORE, slot);
            } else if (type instanceof IRType.Float) {
                methodVisitor.visitInsn(Opcodes.FCONST_0);
                methodVisitor.visitVarInsn(Opcodes.FSTORE, slot);
            } else {
                methodVisitor.visitInsn(Opcodes.ACONST_NULL);
                methodVisitor.visitVarInsn(Opcodes.ASTORE, slot);
            }
        }
    }

    /**
     * Bepaalt voor elk TryCatchRegion het stel beschermde blokken en registreert
     * elke aaneengesloten reeks (in emissie-volgorde) als een aparte
     * exception-table range. De end-labels worden opgehangen achter het laatste
     * blok van elke reeks via {@code runEndLabels}.
     */
    private void planTryRegions(final List<IRBasicBlock> blocks,
                                final FunctionEmitter emitter,
                                final MethodVisitor methodVisitor,
                                final Map<String, List<Label>> runEndLabels) {
        final var labelToBlock = new HashMap<String, IRBasicBlock>();
        for (final var block : blocks) {
            labelToBlock.put(block.label(), block);
        }

        final var regions = new java.util.LinkedHashSet<IRInstruction.TryCatchRegion>();
        final var handlerLabels = new java.util.HashSet<String>();
        final var tryStartLabels = new java.util.HashSet<String>();
        for (final var block : blocks) {
            for (final var instr : block.instructions()) {
                if (instr instanceof IRInstruction.TryCatchRegion region) {
                    regions.add(region);
                    handlerLabels.add(region.handlerLabel());
                    tryStartLabels.add(region.tryStartLabel());
                }
            }
        }

        final var protectedCache = new HashMap<String, Set<String>>();

        for (final var region : regions) {
            if (!labelToBlock.containsKey(region.tryStartLabel())) {
                System.err.println("[TRY-CATCH-SKIP] start-blok ontbreekt: "
                        + "start=" + region.tryStartLabel() + " handler=" + region.handlerLabel());
                continue;
            }
            final var protectedSet = protectedCache.computeIfAbsent(
                    region.tryStartLabel(),
                    s -> computeProtected(
                            blocks, labelToBlock, s, region.tryEndLabel(), handlerLabels, tryStartLabels));
            if (protectedSet.isEmpty()) {
                System.err.println("[TRY-CATCH-SKIP] geen beschermde blokken: "
                        + "start=" + region.tryStartLabel() + " handler=" + region.handlerLabel());
                continue;
            }

            List<String> currentRun = null;
            for (final var block : blocks) {
                if (protectedSet.contains(block.label())) {
                    if (currentRun == null) {
                        currentRun = new java.util.ArrayList<>();
                    }
                    currentRun.add(block.label());
                } else if (currentRun != null) {
                    registerRun(emitter, methodVisitor, region, currentRun, runEndLabels);
                    currentRun = null;
                }
            }
            if (currentRun != null) {
                registerRun(emitter, methodVisitor, region, currentRun, runEndLabels);
            }
        }
    }

    /**
     * Elke handler-entry's label mag UITSLUITEND via de exception-edge bereikbaar
     * zijn; alle normale sprongen (Branch/CondBranch) die op een handler-entry
     * label uitkomen worden naar het body-fallback label (n&aacute;&aacute; de 'astore exn')
     * omgeleid, zodat het normale pad nooit de exception-astore raakt.
     */
    private java.util.Map<String, Label> activeFallBodyByHandler = new HashMap<>();

    private FunctionEmitter activeFunctionEmitter;

    /**
     * De return-I Roger-type van de functie die momenteel wordt geÃ«mitteerd;
     * gebruikt door FunctionEmitter voor de auto-boxing van een primitieve
     * IR-waarde bij een referentie-returntype (JLS 5.1.7) - bv. picocli's
     * CharacterConverter.convert: return van char in IR, Character als
     * method-returntype.
     */
    IRType activeReturnIRType;

    public Label branchTargetLabel(final String branchTargetLabel) {
        final var fallBody = activeFallBodyByHandler.get(branchTargetLabel);
        if (fallBody != null) {
            return fallBody;
        }
        return activeFunctionEmitter.getOrCreateLabel(branchTargetLabel);
    }

    /**
     * True Indien het laatste IR-instructie van dit blok een terminator is
     * (Branch/CondBranch/Return/Throw). Zo niet, valt het blok impliciet
     * door naar het opvolgende label - wat bij handler-entry blokken
     * expliciet moet worden gemaakt (zie de comment in emitFunction).
     */
    private static boolean endsWithTerminator(final IRBasicBlock block) {
        final var instructions = block.instructions();
        if (instructions.isEmpty()) {
            return false;
        }
        return switch (instructions.get(instructions.size() - 1)) {
            case IRInstruction.Branch ignored -> true;
            case IRInstruction.CondBranch ignored -> true;
            case IRInstruction.Return ignored -> true;
            case IRInstruction.Throw ignored -> true;
            default -> false;
        };
    }

    /**
     * Registreert een aaneengesloten reeks beschermde blokken als exception-table
     * range [start beschermd blok, end-label achter laatste beschermd blok].
     */
    private static void registerRun(final FunctionEmitter emitter,
                                    final MethodVisitor methodVisitor,
                                    final IRInstruction.TryCatchRegion region,
                                    final List<String> run,
                                    final Map<String, List<Label>> runEndLabels) {
        final var startLabel = emitter.getOrCreateLabel(run.getFirst());
        final var endLabel = emitter.newLabelFor("runEnd[" + run.getLast() + "]");
        final var handlerLabel = emitter.getOrCreateLabel(region.handlerLabel());
        final var exceptionType = region.exceptionType() != null
                ? BytecodeHelper.toInternalName(region.exceptionType())
                : null;
        methodVisitor.visitTryCatchBlock(startLabel, endLabel, handlerLabel, exceptionType);
        runEndLabels.computeIfAbsent(run.getLast(), k -> new java.util.ArrayList<>()).add(endLabel);
    }

    /**
     * Bereikt de set beschermde blokken van een try-bereik: alle blokken die
     * bereikbaar zijn vanuit de try-start, exclusief handler-blokken, het
     * try-end-blok zelf en andere try-starts (geneste tries).
     */
    private static Set<String> computeProtected(final List<IRBasicBlock> blocks,
                                                final Map<String, IRBasicBlock> labelToBlock,
                                                final String tryStartLabel,
                                                final String tryEndLabel,
                                                final Set<String> handlerLabels,
                                                final Set<String> tryStartLabels) {
        final var order = new HashMap<String, Integer>();
        for (int i = 0; i < blocks.size(); i++) {
            order.put(blocks.get(i).label(), i);
        }

        final var result = new java.util.LinkedHashSet<String>();
        final var work = new java.util.ArrayDeque<String>();
        work.add(tryStartLabel);

        while (!work.isEmpty()) {
            final var label = work.poll();
            if (result.contains(label)) {
                continue;
            }
            final var block = labelToBlock.get(label);
            if (block == null) {
                continue;
            }
            if (label.equals(tryEndLabel)) {
                continue;
            }
            if (handlerLabels.contains(label)) {
                continue;
            }
            if (tryStartLabels.contains(label) && !label.equals(tryStartLabel)) {
                continue;
            }
            result.add(label);

            if (block.getTerminator() instanceof IRInstruction.Branch branch) {
                if (!branch.targetLabel().equals(tryEndLabel)) {
                    work.add(branch.targetLabel());
                }
            } else if (block.getTerminator() instanceof IRInstruction.CondBranch condBranch) {
                if (!condBranch.trueLabel().equals(tryEndLabel)) {
                    work.add(condBranch.trueLabel());
                }
                if (!condBranch.falseLabel().equals(tryEndLabel)) {
                    work.add(condBranch.falseLabel());
                }
            } else if (!block.isTerminated()) {
                final var nextIndex = order.getOrDefault(label, -1) + 1;
                if (nextIndex < blocks.size()) {
                    final var nextLabel = blocks.get(nextIndex).label();
                    if (!nextLabel.equals(tryEndLabel)) {
                        work.add(nextLabel);
                    }
                }
            }
        }

        return result;
    }

    /**
     * Verwijdert blokken die onbereikbaar zijn vanuit het entry-blok of een
     * exception-handler. Onbereikbare blokken (bijv. "try.end"-voortzetting
     * achter een return/throw) doen de ASM-frameberekening ontsporen
     * ("Frame.merge: Index out of bounds") en leveren niks op.
     */
    private static List<IRBasicBlock> pruneUnreachable(final List<IRBasicBlock> linearized) {
        if (linearized.isEmpty()) {
            return linearized;
        }

        final var reachable = new java.util.HashSet<String>();
        reachable.add(linearized.getFirst().label());

        // Handler-blokken zijn alleen via de exception-table bereikbaar.
        for (final var b : linearized) {
            for (final var instr : b.instructions()) {
                if (instr instanceof IRInstruction.TryCatchRegion tc) {
                    reachable.add(tc.handlerLabel());
                }
            }
        }

        var changed = true;
        while (changed) {
            changed = false;
            for (var bi = 0; bi < linearized.size(); bi++) {
                final var b = linearized.get(bi);
                if (!reachable.contains(b.label())) {
                    continue;
                }
                if (b.getTerminator() instanceof IRInstruction.Branch branch) {
                    if (reachable.add(branch.targetLabel())) changed = true;
                } else if (b.getTerminator() instanceof IRInstruction.CondBranch condBranch) {
                    if (reachable.add(condBranch.trueLabel())) changed = true;
                    if (reachable.add(condBranch.falseLabel())) changed = true;
                } else if (!b.isTerminated() && bi + 1 < linearized.size()) {
                    if (reachable.add(linearized.get(bi + 1).label())) changed = true;
                }
            }
        }

        return linearized.stream()
                .filter(b -> reachable.contains(b.label()))
                .toList();
    }

    public byte[] getBytecode() {
        return cw.toByteArray();
    }
}

package io.github.potjerodekool.nabu.compiler.backend.asm2;

import io.github.potjerodekool.nabu.compiler.backend.asm.AsmHelper;
import io.github.potjerodekool.nabu.compiler.backend.asm.Linearizer;
import io.github.potjerodekool.nabu.compiler.backend.ir.PhiElimination;
import io.github.potjerodekool.nabu.compiler.ir.IRBasicBlock;
import io.github.potjerodekool.nabu.compiler.ir.IRField;
import io.github.potjerodekool.nabu.compiler.ir.IRFunction;
import io.github.potjerodekool.nabu.compiler.ir.IRGlobal;
import io.github.potjerodekool.nabu.compiler.ir.IRModule;
import io.github.potjerodekool.nabu.compiler.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.compiler.ir.values.IRValue;
import io.github.potjerodekool.nabu.compiler.lang.Flags;
import io.github.potjerodekool.nabu.compiler.resolve.asm.AccessUtils;
import io.github.potjerodekool.nabu.tools.JavaVersion;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.util.TraceClassVisitor;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Emitteert een heel {@link IRModule} naar een Java-klassebestand.
 *
 * Elke functie wordt eerst door {@link PhiElimination} gehaald (phi's worden
 * vervangen door stores in predecessor-blokken en loads in de header-blokken),
 * daarna geordend door {@link Linearizer} en vervolgens emissie via
 * {@link FunctionEmitter} met een per-functie {@link SlotAllocator}.
 */
public class Asm2ByteCodeEmitter {

    private final ClassWriter cw;
    private final ClassVisitor classVisitor;
    private final Map<String, IRGlobal> globalMap = new HashMap<>();
    private String ownerInternalName;

    public Asm2ByteCodeEmitter() {
        cw = new ClassWriter(
                ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES
        );
        this.classVisitor = new TraceClassVisitor(
                cw,
                new PrintWriter(new StringWriter())
        );
    }

    public IRGlobal getGlobal(final String name) {
        return globalMap.get(name);
    }

    private int resolveModuleAccess(final IRModule module) {
        int access = 0;

        if (module.flags == 0) {
            access = Opcodes.ACC_PUBLIC + Opcodes.ACC_SUPER;
        } else {
            access = Opcodes.ACC_SUPER;

            if (Flags.hasFlag(module.flags, Flags.PUBLIC)) {
                access += Opcodes.ACC_PUBLIC;
            }
            if (Flags.hasFlag(module.flags, Flags.FINAL)) {
                access += Opcodes.ACC_FINAL;
            }
            if (Flags.hasFlag(module.flags, Flags.RECORD)) {
                access += Opcodes.ACC_RECORD;
            }
        }

        return access;
    }

    public void emit(final IRModule module) {
        module.globals().forEach(this::emitGlobal);

        final var javaVersion = JavaVersion.MINIMAL_VERSION;
        final var classVersion = javaVersion.getValue();
        final var access = resolveModuleAccess(module);
        final var internalName = AsmHelper.toInternalName(module.name);
        this.ownerInternalName = internalName;
        final String signature = null;
        final var superName = module.superType() != null
                ? AsmHelper.createDescriptor(module.superType())
                : "java/lang/Object";

        final var interfaces = module.interfaces().stream()
                .map(AsmHelper::createDescriptor)
                .toArray(String[]::new);

        final var fileName = module.sourceFile();

        classVisitor.visit(classVersion, access, internalName, signature, superName, interfaces);
        classVisitor.visitSource(fileName, null);

        module.fields().forEach(this::emitField);

        for (final var function : module.functions()) {
            emitFunction(function);
        }

        classVisitor.visitEnd();
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

        final var descriptor = AsmHelper.createDescriptorWithValues(
                params,
                function.returnType
        );

        final var methodVisitor = classVisitor.visitMethod(
                access,
                name,
                descriptor,
                null,
                null
        );

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

            final var blocks = Linearizer.linearize(function.blocks());
            final var emitter = new FunctionEmitter(methodVisitor, this, slots, ownerInternalName, blocks);

            // Pre-scan voor TryCatchRegion-instructies en registreer de
            // try-catch-blokken vóór de eerste instructie.
            for (final var block : blocks) {
                for (final var instr : block.instructions()) {
                    if (instr instanceof IRInstruction.TryCatchRegion tc) {
                        final var startLabel = emitter.getOrCreateLabel(tc.tryStartLabel());
                        final var endLabel = emitter.getOrCreateLabel(tc.tryEndLabel());
                        final var handlerLabel = emitter.getOrCreateLabel(tc.handlerLabel());
                        final var exceptionType = tc.exceptionType() != null
                                ? AsmHelper.toInternalName(tc.exceptionType())
                                : null;
                        methodVisitor.visitTryCatchBlock(
                                startLabel,
                                endLabel,
                                handlerLabel,
                                exceptionType
                        );
                    }
                }
            }

            try {
                for (final var block : blocks) {
                    emitter.emitBlock(block);
                    for (final var instr : block.instructions()) {
                        emitter.emit(instr);
                    }
                }
                emitter.visitLabel("END");
                emitter.visitLocalVariables(function, function.blocks().getFirst().label());
            } catch (final Exception e) {
                blocks.forEach(Asm2ByteCodeEmitter::printBlock);
                throw e;
            }

            methodVisitor.visitMaxs(-1, -1);
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
        final var descriptor = AsmHelper.createDescriptor(field.type());

        switch (field.kind()) {
            case RECORD_COMPONENT -> {
                final var recordVisitor = classVisitor.visitRecordComponent(
                        name,
                        descriptor,
                        null
                );
                recordVisitor.visitEnd();
            }
            case FIELD -> {
                final var fieldVisitor = classVisitor.visitField(
                        access,
                        field.name(),
                        descriptor,
                        null,
                        null
                );
                fieldVisitor.visitEnd();
            }
        }
    }

    private static void printBlock(final IRBasicBlock block) {
        block.instructions().forEach(System.out::println);
    }

    public byte[] getBytecode() {
        return cw.toByteArray();
    }
}

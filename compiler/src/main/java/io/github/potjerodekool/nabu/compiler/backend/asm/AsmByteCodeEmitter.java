package io.github.potjerodekool.nabu.compiler.backend.asm;

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
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;
import io.github.potjerodekool.nabu.lang.Flags;
import io.github.potjerodekool.nabu.lang.model.element.*;
import io.github.potjerodekool.nabu.resolve.jvm.AccessUtils;
import io.github.potjerodekool.nabu.tools.JavaVersion;
import io.github.potjerodekool.nabu.type.DeclaredType;
import io.github.potjerodekool.nabu.type.TypeMirror;
import org.objectweb.asm.*;
import org.objectweb.asm.util.TraceClassVisitor;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
        this.classVisitor = new TraceClassVisitor(
                cw,
                new PrintWriter(new StringWriter())
        );
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

    public void emit(final IRModule module) {
        module.globals().forEach(this::emitGlobal);

        final var javaVersion = JavaVersion.MINIMAL_VERSION;
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

        final var methodVisitor = classVisitor.visitMethod(
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
                                ? BytecodeHelper.toInternalName(tc.exceptionType())
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

                methodVisitor.visitMaxs(-1, -1);
            } catch (final Exception e) {
                System.err.println("[AsmByteCodeEmitter] Error in function: " + function.name);
                blocks.forEach(AsmByteCodeEmitter::printBlock);
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

    private static void printBlock(final IRBasicBlock block) {
        System.out.println("  Block(" + block.label() + ") [" + block.getBlockType() + "]:");
        block.instructions().forEach(instr -> System.out.println("    " + instr));
    }

    public byte[] getBytecode() {
        return cw.toByteArray();
    }
}

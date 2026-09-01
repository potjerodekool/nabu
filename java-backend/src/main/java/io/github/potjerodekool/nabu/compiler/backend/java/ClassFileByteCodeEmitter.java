package io.github.potjerodekool.nabu.compiler.backend.java;

import io.github.potjerodekool.nabu.backend.ir.PhiElimination;
import io.github.potjerodekool.nabu.backend.jvm.BytecodeHelper;
import io.github.potjerodekool.nabu.resolve.jvm.AccessUtils;
import io.github.potjerodekool.nabu.backend.jvm.Linearizer;
import io.github.potjerodekool.nabu.backend.jvm.SlotAllocator;
import io.github.potjerodekool.nabu.backend.ir.IRBasicBlock;
import io.github.potjerodekool.nabu.backend.ir.IRField;
import io.github.potjerodekool.nabu.backend.ir.IRFunction;
import io.github.potjerodekool.nabu.backend.ir.IRGlobal;
import io.github.potjerodekool.nabu.backend.ir.IRModule;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;
import io.github.potjerodekool.nabu.lang.Flags;

import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;

import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Emitteert een heel {@link IRModule} naar een Java-klassebestand met de
 * Java 24 ClassFile API (java.lang.classfile).
 *
 * Elke functie wordt eerst door {@link PhiElimination} gehaald, daarna
 * geordend door {@link Linearizer} en vervolgens geëmitteerd via
 * {@link JavaFunctionEmitter} met een per-functie {@link SlotAllocator}.
 */
class ClassFileByteCodeEmitter {

    /**
     * JVMS tabel 4.1-B: ACC_RECORD (0x10000). Deze vlag ontbreekt als
     * constante op de ClassFile-interface, daarom hier lokaal gedefinieerd.
     */
    private static final int ACC_RECORD = 0x0001_0000;

    private final Map<String, IRGlobal> globalMap = new HashMap<>();

    private boolean hasMainFunction;

    private String ownerInternalName;

    Map<String, IRGlobal> globals() {
        return globalMap;
    }

    private IRGlobal getGlobal(final String name) {
        return globalMap.get(name);
    }

    private static int resolveModuleAccess(final IRModule module) {
        int access;

        if (module.flags == 0) {
            access = ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER;
        } else {
            if (Flags.hasFlag(module.flags, Flags.INTERFACE)) {
                access = ClassFile.ACC_PUBLIC | ClassFile.ACC_ABSTRACT | ClassFile.ACC_INTERFACE;
            } else {
                access = ClassFile.ACC_SUPER;

                if (Flags.hasFlag(module.flags, Flags.PUBLIC)) {
                    access |= ClassFile.ACC_PUBLIC;
                }
                if (Flags.hasFlag(module.flags, Flags.FINAL)) {
                    access |= ClassFile.ACC_FINAL;
                }
                if (Flags.hasFlag(module.flags, Flags.ABSTRACT)) {
                    access |= ClassFile.ACC_ABSTRACT;
                }
                if (Flags.hasFlag(module.flags, Flags.RECORD)) {
                    access |= ACC_RECORD;
                }
                if (Flags.hasFlag(module.flags, Flags.ENUM)) {
                    access |= ClassFile.ACC_ENUM;
                }
            }
        }

        return access;
    }

    byte[] emit(final IRModule module) {
        hasMainFunction = false;
        module.globals().forEach(this::emitGlobal);

        ownerInternalName = BytecodeHelper.toInternalName(module.name);

        return ClassFile.of().build(
                ClassDesc.ofInternalName(ownerInternalName),
                classBuilder -> buildClass(module, classBuilder)
        );
    }

    private void buildClass(final IRModule module,
                            final ClassBuilder classBuilder) {
        final var javaVersion = io.github.potjerodekool.nabu.tools.JavaVersion.MINIMAL_VERSION;
        classBuilder.withVersion(javaVersion.getValue(), 0);
        classBuilder.withFlags(resolveModuleAccess(module));

        final var superName = module.superType() != null
                ? BytecodeHelper.toInternalName(module.superType())
                : "java/lang/Object";
        classBuilder.withSuperclass(ClassDesc.ofInternalName(superName));

        final var interfaces = module.interfaces().stream()
                .map(BytecodeHelper::toInternalName)
                .map(ClassDesc::ofInternalName)
                .toList();
        if (!interfaces.isEmpty()) {
            classBuilder.withInterfaceSymbols(interfaces);
        }

        final var rawSourceFile = module.sourceFile();
        final var fileName = rawSourceFile.contains("/") || rawSourceFile.contains("\\")
                ? java.nio.file.Path.of(rawSourceFile).getFileName().toString()
                : rawSourceFile;
        AttributeFactories.sourceFile(classBuilder, fileName);

        if (module.sealedClass()) {
            final var permittedSubclasses = module.permittedSubclasses();
            if (!permittedSubclasses.isEmpty()) {
                AttributeFactories.permittedSubclasses(
                        classBuilder,
                        permittedSubclasses.stream()
                                .map(BytecodeHelper::toInternalName)
                                .toList()
                );
            }
        }

        AttributeFactories.annotations(classBuilder, module.annotations());

        for (final var field : module.fields()) {
            emitField(classBuilder, field);
        }

        for (final var function : module.functions()) {
            if (!function.isExternal()
                    && !function.isConstructor()
                    && "main".equals(emittedName(function))) {
                hasMainFunction = true;
            }
            emitFunction(function, classBuilder);
        }

        // Modules zonder main krijgen een lege standaard-entrypoint,
        // zodat de gegenereerde klasse altijd uitvoerbaar is.
        if (!hasMainFunction && !Flags.hasFlag(module.flags, Flags.INTERFACE)) {
            emitDefaultMain(classBuilder);
        }
    }

    private void emitDefaultMain(final ClassBuilder classBuilder) {
        classBuilder.withMethod(
                "main",
                MethodTypeDesc.ofDescriptor("([Ljava/lang/String;)V"),
                ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC,
                methodBuilder -> methodBuilder.withCode(CodeBuilder::return_)
        );
    }

    private static String emittedName(final IRFunction function) {
        var name = function.name;
        final var separatorIndex = name.indexOf('_');
        if (separatorIndex > -1) {
            name = name.substring(separatorIndex + 1);
        }
        if (function.isConstructor()) {
            name = "<init>";
        }
        return name;
    }

    private void emitGlobal(final String name,
                            final IRGlobal global) {
        globalMap.put(name, global);
    }

    private void emitField(final ClassBuilder classBuilder,
                           final IRField field) {
        final var name = field.name();
        final var fieldDesc = ClassDesc.ofDescriptor(BytecodeHelper.createDescriptor(field.type()));

        switch (field.kind()) {
            case RECORD_COMPONENT -> AttributeFactories.recordComponent(
                    classBuilder,
                    name,
                    BytecodeHelper.createDescriptor(field.type()),
                    field.genericSignature(),
                    field.annotations()
            );
            case FIELD -> classBuilder.withField(
                    name,
                    fieldDesc,
                    fieldBuilder -> {
                        fieldBuilder.withFlags(AccessUtils.flagsToAccess(field.flags()));
                        AttributeFactories.annotations(fieldBuilder, field.annotations());
                        AttributeFactories.signature(fieldBuilder, field.genericSignature());
                    }
            );
        }
    }

    private void emitFunction(final IRFunction function,
                              final ClassBuilder classBuilder) {
        if (function.isExternal()) {
            return;
        }

        final var access = AccessUtils.flagsToAccess(function.getFlags());
        final boolean methodIsStatic = Flags.hasFlag(function.getFlags(), Flags.STATIC);

        final var methodName = emittedName(function);

        var params = function.params;

        if (!methodIsStatic && !params.isEmpty()) {
            params = params.size() == 1 ? List.of() : params.subList(1, params.size());
        }

        final var descriptor = BytecodeHelper.createDescriptorWithValues(
                params,
                function.returnType
        );

        final var methodParams = params;

        classBuilder.withMethod(
                methodName,
                MethodTypeDesc.ofDescriptor(descriptor),
                access,
                methodBuilder -> {
                    AttributeFactories.annotations(methodBuilder, function.annotations());
                    AttributeFactories.signature(methodBuilder, function.genericSignature());
                    AttributeFactories.parameterAnnotations(methodBuilder, methodParams, methodIsStatic, function.parameterAnnotations());
                    AttributeFactories.methodParameters(methodBuilder, methodParams);
                    if (!function.blocks().isEmpty()) {
                        methodBuilder.withCode(code -> emitFunctionBody(code, function, methodParams, methodIsStatic));
                    }
                }
        );
    }

    private void emitFunctionBody(final CodeBuilder code,
                                  final IRFunction function,
                                  final List<IRValue> declaredParams,
                                  final boolean isStatic) {
        final var slots = new SlotAllocator();
        allocateParams(slots, function, isStatic);

        PhiElimination.run(function);

        final var blocks = Linearizer.linearize(function.blocks());
        final var emitter = new JavaFunctionEmitter(this, code, slots, ownerInternalName, blocks);

        try {
            for (final var block : blocks) {
                emitter.emitBlock(block);
                for (final var instr : block.instructions()) {
                    emitter.emit(instr);
                }
            }
            emitter.visitLabel("END");
            emitter.emitTryCatchRegions(blocks);
            emitter.visitLocalVariables(function, function.blocks().getFirst().label());
        } catch (final Exception e) {
            System.err.println("[ClassFileByteCodeEmitter] Error in function: " + function.name);
            blocks.forEach(ClassFileByteCodeEmitter::printBlock);
            throw e;
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

    IRGlobal findGlobal(final String name) {
        var global = getGlobal(name);
        if (global == null && !name.startsWith("@")) {
            global = getGlobal("@" + name);
        }
        if (global == null && name.startsWith("@")) {
            global = getGlobal(name.substring(1));
        }
        return global;
    }

    private static void printBlock(final IRBasicBlock block) {
        System.out.println("  Block(" + block.label() + ") [" + block.getBlockType() + "]:");
        block.instructions().forEach(instr -> System.out.println("    " + instr));
    }
}

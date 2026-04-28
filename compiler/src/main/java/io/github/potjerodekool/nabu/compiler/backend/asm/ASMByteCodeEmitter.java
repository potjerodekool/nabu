package io.github.potjerodekool.nabu.compiler.backend.asm;

import io.github.potjerodekool.nabu.compiler.backend.generate.asm.AsmWithStackMethodVisitor;
import io.github.potjerodekool.nabu.compiler.resolve.asm.AccessUtils;
import io.github.potjerodekool.nabu.ir.*;
import io.github.potjerodekool.nabu.ir.values.IRValue;
import io.github.potjerodekool.nabu.lang.Flags;
import io.github.potjerodekool.nabu.tools.JavaVersion;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceClassVisitor;
import org.objectweb.asm.util.TraceMethodVisitor;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ASMByteCodeEmitter implements AsmContext {

    private final ClassWriter cw;
    private final ClassVisitor classVisitor;
    private final Map<String, IRGlobal> globalMap = new HashMap<>();

    public ASMByteCodeEmitter() {
        cw = new ClassWriter(
                ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES
        );

        this.classVisitor = new TraceClassVisitor(
                        cw,
                        new PrintWriter(new StringWriter())
                );
    }

    @Override
    public IRGlobal getGlobal(final String name) {
        return globalMap.get(name);
    }

    private int resolveModuleAccess(final IRModule module) {
        int access = 0;

        if (module.flags == 0) {
            //TODO Tempory fix
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
        final String signature = null;
        final var superName = module.superType() != null ? AsmHelper.createDescriptor(module.superType())
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
        //TODO resolve correct access.
        final var access = AccessUtils.flagsToAccess(function.getFlags());
        final var isStatic = Flags.hasFlag(function.getFlags(), Flags.STATIC);

        var name = function.name;
        final var separatorIndex = name.indexOf('_');
        name = name.substring(separatorIndex + 1);

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

        final var textifier = new Textifier();
        final var mv = new TraceMethodVisitor(methodVisitor, textifier);
        final var visitor = new AsmWithStackMethodVisitor(Opcodes.ASM9, mv);

        final var parameters = params.stream()
                .map(param -> (IRValue.Temp) param)
                .toList();

        for (final var parameter : parameters) {
            final var paramName = parameter.name().substring(1);
            //TODO set correct access
            visitor.visitParameter(
                    paramName,
                    0
            );
        }

        final var instructionEmitter = new InstructionEmitter(
                visitor,
                this,
                0
        );

        if (!function.blocks().isEmpty()) {
            visitor.visitCode();
            final var blocks = Linearizer.linearize(function.blocks());

            try {
                if (!isStatic) {
                    final var thisParam = function.params.getFirst();
                    instructionEmitter.createLocal("this", thisParam.type());
                }

                for (final var param : params) {
                    final var paramName = IRValue.nameOf(param);

                    instructionEmitter.createLocal(
                            paramName,
                            param.type()
                    );
                }



                for (final var block : blocks) {
                    instructionEmitter.emit(block);
                    final var instructions = block.instructions();
                    instructions.forEach(instructionEmitter::emit);
                }
                instructionEmitter.visitLabel("END");
            } catch (final Exception e) {
                blocks.forEach(this::printBlock);
                throw e;
            }
        }

        if (!function.blocks().isEmpty()) {
            instructionEmitter.visitLocalVariables(function);
        }

        try {
            visitor.visitMaxs(-1, -1);
            visitor.visitEnd();
        } catch (final Exception e) {
            final var code = ((TraceClassVisitor) this.classVisitor).p.getText().stream()
                    .map(it -> {
                        if (it instanceof List<?> list) {
                            return list.stream()
                                    .map(Object::toString)
                                    .collect(Collectors.joining());
                        }

                        return it.toString();
                    }).collect(Collectors.joining());

            throw new RuntimeException(code);
        }
    }

    private void emitField(final IRField field) {
        final var access = AccessUtils.flagsToAccess(field.flags());
        final var name = field.name();
        final var descriptor = AsmHelper.createDescriptor(field.type());
        final String signature = null; //TODO

        switch (field.kind()) {
            case RECORD_COMPONENT -> {
                final var recordVisitor = classVisitor.visitRecordComponent(
                        name,
                        descriptor,
                        signature
                );
                recordVisitor.visitEnd();
            }
            case FIELD -> {
                final var fieldVisitor = classVisitor.visitField(
                        access,
                        field.name(),
                        descriptor,
                        signature,
                        null //TODO
                );
                fieldVisitor.visitEnd();
            }
        }
    }

    private void printBlock(final IRBasicBlock block) {
        block.instructions().forEach(instruction -> {
            System.out.println(instruction);
        });
    }

    public byte[] getBytecode() {
        return cw.toByteArray();
    }
}

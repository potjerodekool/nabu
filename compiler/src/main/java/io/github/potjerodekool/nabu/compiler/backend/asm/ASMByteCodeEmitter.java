package io.github.potjerodekool.nabu.compiler.backend.asm;

import io.github.potjerodekool.nabu.ir.IRFunction;
import io.github.potjerodekool.nabu.ir.IRGlobal;
import io.github.potjerodekool.nabu.ir.IRModule;
import io.github.potjerodekool.nabu.ir.types.IRType;
import io.github.potjerodekool.nabu.ir.values.IRValue;
import io.github.potjerodekool.nabu.tools.JavaVersion;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceMethodVisitor;

import java.util.HashMap;
import java.util.Map;

public class ASMByteCodeEmitter implements AsmContext {

    private final ClassWriter classWriter = new ClassWriter(
            ClassWriter.COMPUTE_MAXS + ClassWriter.COMPUTE_FRAMES
    );

    private final Map<String, IRGlobal> globalMap = new HashMap<>();

    @Override
    public IRGlobal getGlobal(final String name) {
        return globalMap.get(name);
    }

    public void emit(final IRModule module) {
        module.globals().forEach(this::emitGlobal);

        final var javaVersion = JavaVersion.MINIMAL_VERSION;
        final var classVersion = javaVersion.getValue();
        final var access = Opcodes.ACC_PUBLIC + Opcodes.ACC_SUPER;
        final var internalName = module.name;
        final String signature = null;
        final var superName = "java/lang/Object";
        final var interfaces = new String[0];
        final var fileName = module.name + ".nabu";

        classWriter.visit(classVersion, access, internalName, signature, superName, interfaces);
        classWriter.visitSource(fileName, null);

        for (final var function : module.functions()) {
            emitFunction(function);
        }

        classWriter.visitEnd();
    }

    private void emitGlobal(final String name, final IRGlobal global) {
        this.globalMap.put(name, global);
    }

    private void emitFunction(final IRFunction function) {
        final var access = Opcodes.ACC_PUBLIC + Opcodes.ACC_STATIC;
        var name = function.name;
        final var separatorIndex = name.indexOf('_');
        name = name.substring(separatorIndex + 1);

        var params = function.params;
        if (!function.isStatic()) {
            params = params.subList(0, params.size() - 1);
        }

        final var descriptor = AsmHelper.createDescriptor(
                params,
                function.returnType,
                this
        );

        final var methodVisitor = classWriter.visitMethod(
                access,
                name,
                descriptor,
                null,
                null
        );
        methodVisitor.visitCode();
        final var textifier = new Textifier();
        final var mv = new TraceMethodVisitor(methodVisitor, textifier);

        if (function.blocks().isEmpty()) {
            methodVisitor.visitInsn(Opcodes.RETURN);
        } else {
            final var instructionEmitter = new InstructionEmitter(
                    mv,
                    this
            );

            for (final var block : function.blocks()) {
                for (final var instruction : block.instructions()) {
                    instructionEmitter.emit(instruction);
                }
            }
        }

        methodVisitor.visitMaxs(-1, -1);
        methodVisitor.visitEnd();
    }

    public byte[] getBytecode() {
        return classWriter.toByteArray();
    }
}

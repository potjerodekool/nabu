package io.github.potjerodekool.nabu.compiler.backend.bytecode;

import io.github.potjerodekool.nabu.backend.ir.CallKind;
import io.github.potjerodekool.nabu.backend.ir.IRBuilder;
import io.github.potjerodekool.nabu.backend.ir.IRModule;
import io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import io.github.potjerodekool.nabu.compiler.io.impl.NabuCFileManager;
import io.github.potjerodekool.nabu.lang.Flags;
import io.github.potjerodekool.nabu.tools.CompilerOption;
import io.github.potjerodekool.nabu.tools.CompilerOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Een library-klasse uit een jar op het classpath (zoals picocli) wordt uit
 * bytecode gecompileerd zodra de broncode haar aanroept.
 */
class ClassPathJarLoweringTest {

    private static byte[] greeterClass() {
        final var cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "lib/Greeter", null, "java/lang/Object", null);
        final var init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        final var hello = cw.visitMethod(Opcodes.ACC_PUBLIC, "hello", "()I", null, null);
        hello.visitCode();
        hello.visitIntInsn(Opcodes.BIPUSH, 42);
        hello.visitInsn(Opcodes.IRETURN);
        hello.visitMaxs(0, 0);
        hello.visitEnd();
        // javac hergebruikt slots: eerst een boolean, daarna een int-teller.
        final var reuse = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "reuse", "()I", null, null);
        reuse.visitCode();
        reuse.visitLdcInsn("true");
        reuse.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "parseBoolean", "(Ljava/lang/String;)Z", false);
        reuse.visitVarInsn(Opcodes.ISTORE, 0);
        reuse.visitInsn(Opcodes.ICONST_0);
        reuse.visitVarInsn(Opcodes.ISTORE, 0);
        reuse.visitIincInsn(0, 12);
        reuse.visitVarInsn(Opcodes.ILOAD, 0);
        reuse.visitInsn(Opcodes.IRETURN);
        reuse.visitMaxs(0, 0);
        reuse.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    void libraryClassFromJarIsLowered(@TempDir final Path tempDir) throws Exception {
        final var jar = tempDir.resolve("lib.jar");
        try (var out = new ZipOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new ZipEntry("lib/Greeter.class"));
            out.write(greeterClass());
            out.closeEntry();
        }

        final var fileManager = new NabuCFileManager();
        fileManager.processOptions(new CompilerOptions.CompilerOptionsBuilder()
                .option(CompilerOption.CLASS_PATH, jar.toString())
                .build());

        final IRType greeter = new IRType.Ptr(IRType.I8, "Llib/Greeter;");
        final IRBuilder b = new IRBuilder("test/Main");
        b.beginFunction("Main_run", IRType.VOID, List.of(), Flags.STATIC, false);
        final var obj = b.emitHeapAlloc("new_obj", greeter);
        b.emitCall(CallKind.SPECIAL, "lib_Greeter_init", IRType.VOID, List.of(), List.of(obj));
        b.emitCall(CallKind.VIRTUAL, "lib.Greeter_hello", IRType.I32, List.of(), List.of(obj));
        b.emitCall(CallKind.STATIC, "lib.Greeter_reuse", IRType.I32, List.of(), List.of());
        b.emitReturn(null);
        b.endFunction();

        final List<IRModule> modules = new BytecodeReachabilityCompiler(fileManager, Set.of())
                .compileClosure(List.of(), List.of(b.build()));

        final var names = modules.stream()
                .filter(m -> m.name.replace('/', '.').equals("lib.Greeter"))
                .flatMap(m -> m.functions().stream())
                .map(f -> f.name)
                .toList();
        assertTrue(names.stream().anyMatch(n -> n.endsWith("Greeter_init")), names.toString());
        assertTrue(names.stream().anyMatch(n -> n.endsWith("Greeter_hello")), names.toString());

        // Het hergebruikte slot is een i32, geen i1 van de eerste (boolean) store.
        final var reuse = modules.stream()
                .flatMap(m -> m.functions().stream())
                .filter(f -> f.name.endsWith("Greeter_reuse"))
                .findFirst()
                .orElseThrow();
        final var slotTypes = reuse.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(insn -> insn instanceof IRInstruction.Alloca)
                .map(insn -> ((IRInstruction.Alloca) insn).allocType())
                .toList();
        assertTrue(slotTypes.contains(IRType.I32), slotTypes.toString());
        assertTrue(slotTypes.stream().noneMatch(type -> type instanceof IRType.Bool), slotTypes.toString());
    }
}

package io.github.potjerodekool.nabu.compiler.backend.bytecode;

import io.github.potjerodekool.nabu.backend.ir.CallKind;
import io.github.potjerodekool.nabu.backend.ir.IRBuilder;
import io.github.potjerodekool.nabu.backend.ir.IRFunction;
import io.github.potjerodekool.nabu.backend.ir.IRModule;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The closure only contains what can run: overrides of instantiated classes
 * (rapid type analysis), static initializers of initialized classes, and no
 * code behind methods that cannot work natively.
 */
class ReachabilityPrecisionTest {

    /**
     * lib/Base: {@code int value()} and {@code static int twice(Base b)}, which
     * calls {@code b.value()} and tests {@code b instanceof Unused}.
     * lib/Sub and lib/Unused override {@code value()}; Unused also has a
     * static initializer. The source only instantiates Sub.
     */
    private static void writeJar(final Path jar) throws Exception {
        try (var out = new ZipOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new ZipEntry("lib/Base.class"));
            out.write(baseClass());
            out.closeEntry();
            out.putNextEntry(new ZipEntry("lib/Sub.class"));
            out.write(subclass("lib/Sub", 2, false));
            out.closeEntry();
            out.putNextEntry(new ZipEntry("lib/Unused.class"));
            out.write(subclass("lib/Unused", 3, true));
            out.closeEntry();
        }
    }

    private static byte[] baseClass() {
        final var cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "lib/Base", null, "java/lang/Object", null);
        constructor(cw, "java/lang/Object");
        intMethod(cw, 1);
        final var twice = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "twice", "(Llib/Base;)I", null, null);
        twice.visitCode();
        twice.visitVarInsn(Opcodes.ALOAD, 0);
        twice.visitTypeInsn(Opcodes.INSTANCEOF, "lib/Unused");
        twice.visitInsn(Opcodes.POP);
        twice.visitVarInsn(Opcodes.ALOAD, 0);
        twice.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "lib/Base", "value", "()I", false);
        twice.visitInsn(Opcodes.IRETURN);
        twice.visitMaxs(0, 0);
        twice.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] subclass(final String name, final int value, final boolean withClinit) {
        final var cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "lib/Base", null);
        constructor(cw, "lib/Base");
        intMethod(cw, value);
        if (withClinit) {
            cw.visitField(Opcodes.ACC_STATIC, "COUNTER", "I", null, null).visitEnd();
            final var clinit = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            clinit.visitCode();
            clinit.visitIntInsn(Opcodes.BIPUSH, 7);
            clinit.visitFieldInsn(Opcodes.PUTSTATIC, name, "COUNTER", "I");
            clinit.visitInsn(Opcodes.RETURN);
            clinit.visitMaxs(0, 0);
            clinit.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void constructor(final ClassWriter cw, final String superName) {
        final var init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
    }

    private static void intMethod(final ClassWriter cw, final int value) {
        final var method = cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "()I", null, null);
        method.visitCode();
        method.visitIntInsn(Opcodes.BIPUSH, value);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static List<IRFunction> functionsOf(final List<IRModule> modules, final String className) {
        return modules.stream()
                .filter(m -> m.name.replace('/', '.').equals(className))
                .flatMap(m -> m.functions().stream())
                .toList();
    }

    private static boolean hasBody(final List<IRFunction> functions, final String suffix) {
        return functions.stream().anyMatch(f -> f.name.endsWith(suffix) && !f.blocks().isEmpty());
    }

    @Test
    void onlyInstantiatedClassesGetOverridesAndOnlyInitializedClassesGetClinit(@TempDir final Path tempDir)
            throws Exception {
        final var jar = tempDir.resolve("lib.jar");
        writeJar(jar);
        final var fileManager = new NabuCFileManager();
        fileManager.processOptions(new CompilerOptions.CompilerOptionsBuilder()
                .option(CompilerOption.CLASS_PATH, jar.toString())
                .build());

        final IRType sub = new IRType.Ptr(IRType.I8, "Llib/Sub;");
        final IRBuilder b = new IRBuilder("test/Main");
        b.beginFunction("Main_run", IRType.VOID, List.of(), Flags.STATIC, false);
        final var obj = b.emitHeapAlloc("new_obj", sub);
        b.emitCall(CallKind.SPECIAL, "lib_Sub_init", IRType.VOID, List.of(), List.of(obj));
        b.emitCall(CallKind.STATIC, "lib.Base_twice", IRType.I32, List.of(), List.of(obj));
        b.emitReturn(null);
        b.endFunction();

        final List<IRModule> modules = new BytecodeReachabilityCompiler(fileManager, Set.of())
                .compileClosure(List.of(), List.of(b.build()));

        final var subFunctions = functionsOf(modules, "lib.Sub");
        assertTrue(hasBody(subFunctions, "Sub_value"), "the instantiated subclass's override: " + subFunctions);

        // Base has no instances: the call through Base only needs the slot.
        final var baseFunctions = functionsOf(modules, "lib.Base");
        assertTrue(baseFunctions.stream().anyMatch(f -> f.name.endsWith("Base_value")),
                "Base.value keeps its vtable slot: " + baseFunctions);
        assertFalse(hasBody(baseFunctions, "Base_value"), "Base.value has no body: " + baseFunctions);

        // Unused only appears in an instanceof: no override, no initializer.
        final var unusedFunctions = functionsOf(modules, "lib.Unused");
        assertFalse(hasBody(unusedFunctions, "Unused_value"), unusedFunctions.toString());
        assertTrue(unusedFunctions.stream().noneMatch(f -> f.name.endsWith("_clinit")), unusedFunctions.toString());
    }

    @Test
    void runtimeBytecodeGenerationStaysOutOfTheClosure() {
        // Only the runtime roots (System.out/err setup).
        final List<IRModule> modules = new BytecodeReachabilityCompiler(null, Set.of())
                .compileClosure(List.of(), List.of());

        final var classFileApi = modules.stream()
                .map(m -> m.name.replace('/', '.'))
                .filter(name -> name.startsWith("jdk.internal.classfile") || name.startsWith("java.lang.classfile.impl"))
                .toList();
        assertEquals(List.of(), classFileApi);

        final var compileToBytecode = modules.stream()
                .filter(m -> m.name.replace('/', '.').equals("java.lang.invoke.LambdaForm"))
                .flatMap(m -> m.functions().stream())
                .filter(f -> f.name.endsWith("LambdaForm_compileToBytecode"))
                .toList();
        compileToBytecode.forEach(f -> assertTrue(f.blocks().stream()
                        .flatMap(block -> block.instructions().stream())
                        .noneMatch(insn -> insn.toString().contains("InvokerBytecodeGenerator")),
                "compileToBytecode only throws"));
    }
}

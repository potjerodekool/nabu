package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.backend.CompileOptions;
import io.github.potjerodekool.nabu.backend.ir.CallKind;
import io.github.potjerodekool.nabu.backend.ir.IRBasicBlock;
import io.github.potjerodekool.nabu.backend.ir.IRBuilder;
import io.github.potjerodekool.nabu.backend.ir.IRModule;
import io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fase 3 (reflectie-runtime-e2e): de E2-emissie wordt door de runtime
 * geconsumeerd — een échte gelinkte en gedraaide native exe bewijst:
 *  1. nabu_lookup_reflection vindt de greet/GreetCommand-metadata;
 *  2. nabu_reflect_field_offset geeft offsets voor count/name (en -1 voor
 *     een onbekend veld);
 *  3. nabu_reflect_set_i32/get_i32 doen een reflectieve Field.set/get
 *     (write 5, lees 5 terug) op de runtime-offset;
 *  4. nabu_get_field_annotation vindt de @Option-entry op veld `name` maar
 *     NIET op `count`; nabu_get_annotation (klasse-niveau) vindt niets.
 *
 * Exit-code 0 = alle checks geslaagd; 10..15 geeft de eerste faalstap.
 */
class ReflectionRuntimeE2eTest {

    private static final String GREET = TestReflectionModel.GREET_INTERNAL;
    private static final String OPTION = TestReflectionModel.OPTION_INTERNAL;

    private static final IRType I8_PTR = new IRType.Ptr(IRType.I8);
    private static final IRType GREET_PTR = new IRType.Ptr(IRType.I8, "Lgreet/GreetCommand;");

    private static final String GREET_REFLECT_CONFIG = """
            [
              {
                "name" : "greet.GreetCommand",
                "allDeclaredConstructors" : true,
                "allPublicMethods" : true,
                "fields" : [
                  { "name" : "count" },
                  { "name" : "name" }
                ]
              }
            ]
            """;

    @TempDir
    Path tempDir;

    @Test
    void reflectionMetadataDrivesFieldGetSetAndAnnotationLookup() throws Exception {
        final var genDir = tempDir.resolve("META-INF").resolve("native-image")
                .resolve("picocli-generated");
        Files.createDirectories(genDir);
        Files.writeString(genDir.resolve("reflect-config.json"), GREET_REFLECT_CONFIG);

        var root = new File(".").getAbsoluteFile();
        while (!root.isDirectory() || !"nabu".equals(root.getName())) {
            root = root.getParentFile();
        }
        Path runtime = new File(root, "runtime/nabu_runtime.c").toPath();
        System.setProperty("nabu.runtime", runtime.toAbsolutePath().toString());

        CompileOptions opts = CompileOptions.forTarget("x86_64-w64-windows-gnu");
        Path out = tempDir.resolve("reflect-e2e.o");
        new NativeLLVMBackend().compile(
                List.of(mainModule(), TestReflectionModel.greetCommandModule()), opts, out);

        final String ll = Files.readString(tempDir.resolve("reflect-e2e.ll"));
        Files.copy(tempDir.resolve("reflect-e2e.ll"),
                Path.of("C:/Users/evert/AppData/Local/Temp/opencode/reflect_e2e.ll"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        assertTrue(ll.contains("nabu_register_reflection"),
                "reflectie-registratie ontbreekt in .ll:\n" + ll);
        assertTrue(ll.contains("nabu_reflect_get_i32"),
                "aanroep nabu_reflect_get_i32 ontbreekt:\n" + ll);

        Path exe = tempDir.resolve("reflect-e2e.exe");
        assertTrue(Files.exists(exe), ".exe niet aangemaakt: " + exe);

        String extraBin = "C:\\programs\\mingw64\\mingw64\\bin";
        ProcessBuilder pb = new ProcessBuilder(exe.toString());
        pb.environment().compute("Path", (k, v) -> extraBin + ";" + (v == null ? "" : v));
        Process proc = pb.redirectErrorStream(true).start();
        String outStr = new String(proc.getInputStream().readAllBytes());
        int exit = proc.waitFor();
        assertEquals(0, exit,
                "Expected exit 0 (reflectief get/set + annotatie-lookup). Output:\n" + outStr);
    }

    /**
     * main():
     *   obj = new greet.GreetCommand
     *   lookup + offsets + get/set + annotaties, exit 10..15 bij falen.
     */
    private IRModule mainModule() {
        final IRBuilder b = new IRBuilder("test/ReflectE2eMain");

        b.declareExternalFunction("nabu_lookup_reflection",
                IRType.fn(I8_PTR, I8_PTR));
        b.declareExternalFunction("nabu_reflect_field_offset",
                IRType.fn(IRType.I64, I8_PTR, I8_PTR));
        b.declareExternalFunction("nabu_reflect_set_i32",
                IRType.fn(IRType.VOID, I8_PTR, I8_PTR, I8_PTR, IRType.I32));
        b.declareExternalFunction("nabu_reflect_get_i32",
                IRType.fn(IRType.I32, I8_PTR, I8_PTR, I8_PTR));
        b.declareExternalFunction("nabu_get_field_annotation",
                IRType.fn(I8_PTR, I8_PTR, I8_PTR, I8_PTR));
        b.declareExternalFunction("nabu_get_annotation",
                IRType.fn(I8_PTR, I8_PTR, I8_PTR));

        b.beginFunction("main", IRType.I32, List.of(), 0L, false);

        final IRValue obj = b.emitHeapAlloc("obj", GREET_PTR);

        final IRValue info = b.emitCall(CallKind.STATIC, "nabu_lookup_reflection", I8_PTR,
                List.of(I8_PTR), List.of(s(b, GREET)));
        returnIf(b, b.emitBinaryOp(IRInstruction.BinaryOp.Op.EQ, info, IRValue.nullPtr(IRType.I8)), 10);

        final IRValue countOff = fieldOffset(b, "count");
        final IRValue nameOff = fieldOffset(b, "name");
        final IRValue bogusOff = fieldOffset(b, "bogus");
        returnIf(b, b.emitBinaryOp(IRInstruction.BinaryOp.Op.LT, countOff, b.constInt(0)), 11);
        returnIf(b, b.emitBinaryOp(IRInstruction.BinaryOp.Op.LT, nameOff, b.constInt(0)), 11);
        returnIf(b, b.emitBinaryOp(IRInstruction.BinaryOp.Op.GTE, bogusOff, b.constInt(0)), 11);

        // Reflectief Field.set/get op count: schrijf 5, lees 5 terug.
        b.emitCall(CallKind.STATIC, "nabu_reflect_set_i32", IRType.VOID,
                List.of(I8_PTR, I8_PTR, I8_PTR, IRType.I32),
                List.of(obj, s(b, GREET), s(b, "count"), b.constInt(5)));
        final IRValue readBack = b.emitCall(CallKind.STATIC, "nabu_reflect_get_i32", IRType.I32,
                List.of(I8_PTR, I8_PTR, I8_PTR),
                List.of(obj, s(b, GREET), s(b, "count")));
        returnIf(b, b.emitBinaryOp(IRInstruction.BinaryOp.Op.NEQ, readBack, b.constInt(5)), 12);

        // Annotaties: name heeft @Option, count niet, klasse-niveau niets.
        final IRValue annName = b.emitCall(CallKind.STATIC, "nabu_get_field_annotation", I8_PTR,
                List.of(I8_PTR, I8_PTR, I8_PTR),
                List.of(s(b, GREET), s(b, "name"), s(b, OPTION)));
        returnIf(b, b.emitBinaryOp(IRInstruction.BinaryOp.Op.EQ, annName, IRValue.nullPtr(IRType.I8)), 13);

        final IRValue annCount = b.emitCall(CallKind.STATIC, "nabu_get_field_annotation", I8_PTR,
                List.of(I8_PTR, I8_PTR, I8_PTR),
                List.of(s(b, GREET), s(b, "count"), s(b, OPTION)));
        returnIf(b, b.emitBinaryOp(IRInstruction.BinaryOp.Op.NEQ, annCount, IRValue.nullPtr(IRType.I8)), 14);

        final IRValue annClass = b.emitCall(CallKind.STATIC, "nabu_get_annotation", I8_PTR,
                List.of(I8_PTR, I8_PTR),
                List.of(s(b, GREET), s(b, OPTION)));
        returnIf(b, b.emitBinaryOp(IRInstruction.BinaryOp.Op.NEQ, annClass, IRValue.nullPtr(IRType.I8)), 15);

        b.emitReturn(b.constInt(0));
        b.endFunction();
        return b.build();
    }

    private IRValue fieldOffset(final IRBuilder b, final String field) {
        return b.emitCall(CallKind.STATIC, "nabu_reflect_field_offset", IRType.I64,
                List.of(I8_PTR, I8_PTR),
                List.of(s(b, GREET), s(b, field)));
    }

    private IRValue s(final IRBuilder b, final String value) {
        return b.constString(value);
    }

    /** Retourneert exitCode als de conditie waar is (anders verder). */
    private static void returnIf(final IRBuilder b, final IRValue cond, final int exitCode) {
        final IRBasicBlock current = b.currentBlock();
        final IRBasicBlock fail = b.beginBlock("fail." + exitCode);
        final IRBasicBlock cont = b.beginBlock("continue");
        b.setCurrentBlock(current);
        b.emitCondBranch(cond, fail, cont);
        b.setCurrentBlock(fail);
        b.emitReturn(b.constInt(exitCode));
        b.setCurrentBlock(cont);
    }
}
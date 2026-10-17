package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.backend.CompileOptions;
import io.github.potjerodekool.nabu.backend.ir.CallKind;
import io.github.potjerodekool.nabu.backend.ir.IRBasicBlock;
import io.github.potjerodekool.nabu.backend.ir.IRBuilder;
import io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;
import io.github.potjerodekool.nabu.lang.Flags;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Blokken staan in IR-volgorde niet altijd vóór hun gebruikers: een
 * lus-update-blok komt vóór de body-tail die de nieuwe waarde berekent.
 * In die volgorde emitteren gaf {@code undef} voor de nog niet geëmitteerde
 * waarde (picocli's quoteElements verloor zo zijn accumulator); in reverse
 * post-order is de definitie er altijd eerst.
 */
class BlockEmissionOrderTest {

    @TempDir
    Path tempDir;

    @Test
    void useInEarlierLaidOutBlockSeesTheDefinition() throws Exception {
        IRBuilder b = new IRBuilder("test/Main");
        var param = new IRValue.Temp("x", IRType.I32);
        b.beginFunction("Main_run", IRType.I32, List.of(param), Flags.STATIC, false);
        final IRBasicBlock entry = b.currentBlock();
        final IRBasicBlock update = b.beginBlock("for.update");
        final IRBasicBlock body = b.beginBlock("for.body");

        b.setCurrentBlock(entry);
        b.emitBranch(body);

        b.setCurrentBlock(body);
        final IRValue sum = b.emitBinaryOp(IRInstruction.BinaryOp.Op.ADD, param, b.constInt(41));
        b.emitBranch(update);

        b.setCurrentBlock(update);
        b.emitCall(CallKind.STATIC, "ext_use", IRType.VOID, List.of(IRType.I32), List.of(sum));
        b.emitReturn(sum);
        b.endFunction();

        Path out = tempDir.resolve("out.o");
        new NativeLLVMBackend().compileToObject(List.of(b.build()),
                new CompileOptions(CompileOptions.OptLevel.NONE, false, null,
                        CompileOptions.GcStrategy.BOEHM,
                        io.github.potjerodekool.nabu.tools.JavaVersion.MINIMAL_VERSION), out);
        String ll = Files.readString(tempDir.resolve("out.ll"));
        String run = ll.substring(ll.indexOf("define i32 @Main_run("));
        run = run.substring(0, run.indexOf("\n}"));

        assertFalse(run.contains("undef"), "de waarde uit for.body hoort gebruikt te worden:\n" + run);
        assertTrue(run.contains("add i32"), run);
    }
}

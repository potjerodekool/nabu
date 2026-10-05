package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.backend.CompileOptions;
import io.github.potjerodekool.nabu.backend.ir.CallKind;
import io.github.potjerodekool.nabu.backend.ir.IRBasicBlock;
import io.github.potjerodekool.nabu.backend.ir.IRBuilder;
import io.github.potjerodekool.nabu.backend.ir.IRModule;
import io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;
import io.github.potjerodekool.nabu.tools.JavaVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REFCOUNT-e2e: de gegenereerde IR wordt door de runtime geconsumeerd — een
 * échte gelinkte en gedraaide native exe bewijst dat de teller klopt.
 *
 * <p>{@code churn()} alloceert twee objecten in één lokale referentie-slot en
 * overschrijft die slot daarmee. Dat moet precies twee retains opleveren en
 * twee échte releases: één bij de overschrijving (het object dat doodgaat) en
 * één in de exit-pad (het object dat in de slot achterblijft). {@code main}
 * leest daarna de tellers uit de runtime en vergelijkt ze.
 *
 * <p>De check gebeurt in {@code main} en dus ná de exit-pad van {@code churn}:
 * daarom staat het werk in een aparte functie. Zou het in {@code main} zelf
 * staan, dan waren de releases van die frame nog niet uitgevoerd op het
 * moment dat de tellers worden gelezen.
 *
 * <p>Exit-code 0 = geslaagd; 20..22 geeft de eerste faalstap.
 */
class RefCountRuntimeE2eTest {

    private static final IRType BOX = new IRType.Ptr(IRType.I8, "Ltest/Box;");

    @TempDir
    Path tempDir;

    @Test
    void everyRetainIsMatchedByExactlyOneRelease() throws Exception {
        NativeToolchain.assumeGcc();

        var root = new File(".").getAbsoluteFile();
        while (!root.isDirectory() || !"nabu".equals(root.getName())) {
            root = root.getParentFile();
        }
        System.setProperty("nabu.runtime",
                new File(root, "runtime/nabu_runtime.c").getAbsolutePath());

        final CompileOptions opts = new CompileOptions(
                CompileOptions.OptLevel.DEFAULT,
                false,
                "x86_64-w64-windows-gnu",
                CompileOptions.GcStrategy.REFCOUNT,
                JavaVersion.MINIMAL_VERSION);
        final Path out = tempDir.resolve("rc-e2e.o");
        new NativeLLVMBackend().compile(List.of(module()), opts, out);

        final String ll = Files.readString(tempDir.resolve("rc-e2e.ll"));
        assertTrue(ll.contains("call void @nabu_retain"),
                "geen retain in de .ll:\n" + ll);
        assertTrue(ll.contains("call void @nabu_release"),
                "geen release in de .ll:\n" + ll);
        // De initialisatie-vlag voorkomt dat ongeïnitialiseerd alloca-geheugen
        // als pointer wordt vrijgegeven.
        assertTrue(ll.contains("nabu.rc.init."), ll);
        // De teller moet vanaf 0 beginnen, anders komt geen object ooit vrij.
        assertTrue(ll.contains(".refcount.slot"), ll);

        final Path exe = tempDir.resolve("rc-e2e.exe");
        assertTrue(Files.exists(exe), ".exe niet aangemaakt: " + exe);

        final Process proc = NativeToolchain.run(exe)
                .redirectErrorStream(true)
                .start();
        final String output = new String(proc.getInputStream().readAllBytes());
        assertEquals(0, proc.waitFor(),
                "Expected exit 0 (retain/release in balans). Output:\n" + output);
    }

    private IRModule module() {
        final IRBuilder b = new IRBuilder("test/Box");
        final IRType i8ptr = new IRType.Ptr(IRType.I8);

        // long nabu_rc_retain_count() / long nabu_rc_release_count()
        b.declareExternalFunction("nabu_rc_retain_count", IRType.fn(IRType.I64));
        b.declareExternalFunction("nabu_rc_release_count", IRType.fn(IRType.I64));
        // int nabu_ref_count(i8*)
        b.declareExternalFunction("nabu_ref_count", IRType.fn(IRType.I32, i8ptr));

        // churn(): int — twee allocs in één slot, de tweede overschrijft de
        // eerste, en retourneert de teller van het tweede object vlak vóór de
        // exit-pad. Dat moet precies 1 zijn: één retain op een header die op 0
        // begint.
        b.beginFunction("churn", IRType.I32, List.of(), 0L, false);
        final IRValue slot = b.emitAlloca("s", BOX);
        b.emitStore(slot, b.emitHeapAlloc("a", BOX));
        final IRValue last = b.emitHeapAlloc("b2", BOX);
        b.emitStore(slot, last);
        final IRValue count = b.emitCall(CallKind.STATIC, "nabu_ref_count", IRType.I32,
                List.of(i8ptr), List.of(last));
        b.emitReturn(count);
        b.endFunction();

        b.beginFunction("main", IRType.I32, List.of(), 0L, false);
        final IRValue observed = b.emitCall(CallKind.STATIC, "churn", IRType.I32,
                List.of(), List.of());

        final IRValue retains = b.emitCall(CallKind.STATIC, "nabu_rc_retain_count",
                IRType.I64, List.of(), List.of());
        final IRValue releases = b.emitCall(CallKind.STATIC, "nabu_rc_release_count",
                IRType.I64, List.of(), List.of());

        // De teller van het laatste object moet op 1 staan: header op 0, één retain.
        returnIf(b, b.emitBinaryOp(IRInstruction.BinaryOp.Op.NEQ, observed, b.constInt(1)), 20);
        // Er moet iets gebeurd zijn, anders bewijst de vergelijking niets.
        returnIf(b, b.emitBinaryOp(IRInstruction.BinaryOp.Op.LTE, retains, b.constInt(0)), 21);
        // Iedere retain hoort precies één release.
        returnIf(b, b.emitBinaryOp(IRInstruction.BinaryOp.Op.NEQ, retains, releases), 22);
        b.emitReturn(b.constInt(0));
        b.endFunction();

        return b.build();
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

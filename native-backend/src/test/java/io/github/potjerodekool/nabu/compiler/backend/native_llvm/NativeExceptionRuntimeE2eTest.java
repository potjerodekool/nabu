package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.backend.CompileOptions;
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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exception-handling-e2e: een échte gelinkte en gedraaide native exe waarin een
 * exception wordt gegooid én gevangen.
 *
 * <p>Programma (exit's als de dispatch werkt):
 * <pre>
 *   main:
 *     try.body:   exc = alloc "Ltest/Exception;"; throw exc
 *     try.catch.0 (java/lang/Exception):
 *                 als instanceOf(%exn.try.catch.0, "Ltest/Exception;") -> return 0
 *                 anders                                      -> return 24
 *     try.catch.1 (java/io/IOException): return 1   (bij match -> 1 = clause 2 bereikt)
 *     try.end:    return 2                            (throw ontsnapte de catch)
 * </pre>
 *
 * <p>Exit 0 bewijst de hele keten: nabu_throw → Itanium unwinder → landingpad →
 * nabu_catch → nabu_can_catch in de juiste volgorde, en hetzelfde object in de
 * handler. Exit 1 zou bewijzen dat de eerste clause wordt overgeslagen (het
 * bug-patroon waarbij alle invokes naar de laatste handler wijzen), exit 24 dat
 * het gevangen object niet het gegooiende object is, en abort ("onbehandelde
 * exception") dat de rethrow niets meer kan vangen.
 *
 * <p>De clauses kiezen we gelijk aan het gegooid type ("test/Exception"): de
 * runtime-synthetiseerde type-info van een handgebouwd object heeft geen
 * super-keten, dus nabu_can_catch matcht alleen op exacte interne naam. (Dit
 * is bewust anders dan de batch-gecompileerde klassen, waar de backend de
 * super-keten in de type-info-global emitteert.)
 *
 * <p>Gelinkt wordt handmatig met MinGW-gcc plus nabu_runtime.c, net als in
 * {@link BoehmRuntimeE2eTest}: het object dat de LLVM-backend zelf linkt wordt op
 * deze machine door de AV vastgehouden (CreateProcess error=5).
 */
class NativeExceptionRuntimeE2eTest {

    private static final IRType EXC_TYPE = new IRType.Ptr(IRType.I8, "Ltest/Exception;");

    @TempDir
    Path tempDir;

    @Test
    void thrownExceptionIsCaughtByTheMatchingClause() throws Exception {
        final String gcc = NativeToolchain.assumeGcc();

        final CompileOptions opts = new CompileOptions(
                CompileOptions.OptLevel.DEFAULT,
                false,
                "x86_64-w64-windows-gnu",
                CompileOptions.GcStrategy.NONE,
                JavaVersion.MINIMAL_VERSION);
        final Path obj = tempDir.resolve("exception-e2e.o");
        new NativeLLVMBackend().compileToObject(module(), opts, obj);

        final Path exe = tempDir.resolve("exception-e2e.exe");
        final List<String> cmd = new ArrayList<>(List.of(
                gcc, obj.toString(), runtimeSource().toString(), "-o", exe.toString()));
        final Process link = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        final String linkOut = new String(link.getInputStream().readAllBytes());
        assertEquals(0, link.waitFor(),
                "linken mislukt: " + String.join(" ", cmd) + "\n" + linkOut);

        final Process proc = NativeToolchain.run(exe).redirectErrorStream(true).start();
        final String output = new String(proc.getInputStream().readAllBytes());
        assertEquals(0, proc.waitFor(),
                "Exception werd niet gevangen in de eerste clause. Output:\n" + output);
    }

    /**
     * Een try met twee clauses waarvan de eerste niet matcht: de tweede moet
     * alsnog vangen. Onder de oude codegeneratie (alle invokes naar de laatste
     * handler, en een verse {@code nabu_throw} bij een mismatch) belandde dit
     * in een abort; nu loopt de dispatch door naar clause 2.
     */
    @Test
    void nonMatchingFirstClauseFallsThroughToTheNext() throws Exception {
        final String gcc = NativeToolchain.assumeGcc();

        final CompileOptions opts = new CompileOptions(
                CompileOptions.OptLevel.DEFAULT,
                false,
                "x86_64-w64-windows-gnu",
                CompileOptions.GcStrategy.NONE,
                JavaVersion.MINIMAL_VERSION);
        final Path obj = tempDir.resolve("exception-fallthrough.o");
        new NativeLLVMBackend().compileToObject(fallThroughModule(), opts, obj);

        final Path exe = tempDir.resolve("exception-fallthrough.exe");
        final List<String> cmd = new ArrayList<>(List.of(
                gcc, obj.toString(), runtimeSource().toString(), "-o", exe.toString()));
        final Process link = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        final String linkOut = new String(link.getInputStream().readAllBytes());
        assertEquals(0, link.waitFor(),
                "linken mislukt: " + String.join(" ", cmd) + "\n" + linkOut);

        final Process proc = NativeToolchain.run(exe).redirectErrorStream(true).start();
        final String output = new String(proc.getInputStream().readAllBytes());
        assertEquals(0, proc.waitFor(),
                "De tweede catch-clause ving de exception niet. Output:\n" + output);
    }

    // -------------------------------------------------------
    // De programma's
    // -------------------------------------------------------

    /** {@code catch (Exception e)} — de eerste clause matcht. */
    private IRModule module() {
        return buildModule("test/Exception", false);
    }

    /**
     * {@code catch (IOException e)} gevolgd door {@code catch (Exception e)}:
     * de eerste matcht niet, dus de tweede moet vangen.
     */
    private IRModule fallThroughModule() {
        return buildModule("java/io/IOException", true);
    }

    /**
     * Bouwt een main met één try en twee clauses. De eerste clause heeft het
     * type {@code firstClause}; de tweede is altijd {@code test/Exception} (het
     * gegooid type) en retourneert exit 0. Zo is exit 0 zowel "de exception
     * werd gevangen" als "de clause die matchte is de verwachte".
     */
    private IRModule buildModule(final String firstClause, final boolean firstFails) {
        final IRBuilder b = new IRBuilder("test/Main");
        b.beginFunction("main", IRType.I32, List.of(), 0L, false);

        final IRBasicBlock entry = b.currentBlock();
        final IRBasicBlock tryBody = b.beginBlock("try.body");
        final IRBasicBlock tryEnd = b.beginBlock("try.end");
        final IRBasicBlock catch0 = b.beginBlock("try.catch.0");
        final IRBasicBlock catch1 = b.beginBlock("try.catch.1");

        b.setCurrentBlock(entry);
        b.emitBranch(tryBody);

        b.setCurrentBlock(tryBody);
        final IRValue exc = b.emitHeapAlloc("exc", EXC_TYPE);
        tryBody.add(new IRInstruction.Throw(exc, EXC_TYPE, b.currentLocation()));

        // Clause 1. Bij een matchende eerste clause bewijzen we ook dát het
        // gevangen object het gegooiende is; anders is dit een dood pad en
        // geven we een herkenbare faalcode terug.
        b.setCurrentBlock(catch0);
        catch0.add(0, new IRInstruction.TryCatchRegion(tryBody.label(), tryEnd.label(),
                catch0.label(), firstClause, b.currentLocation()));
        if (firstFails) {
            b.emitReturn(b.constInt(23));
        } else {
            final IRValue caught = new IRValue.Temp("%exn." + catch0.label(), EXC_TYPE);
            final IRValue isExc = b.emitInstanceOf(caught, EXC_TYPE);
            final IRBasicBlock current = b.currentBlock();
            final IRBasicBlock fail = b.beginBlock("catch.0.wrongObject");
            final IRBasicBlock done = b.beginBlock("catch.0.done");
            b.setCurrentBlock(current);
            b.emitCondBranch(isExc, done, fail);
            b.setCurrentBlock(fail);
            b.emitReturn(b.constInt(24));
            b.setCurrentBlock(done);
            b.emitReturn(b.constInt(0));
        }

        // Clause 2 vangt het gegooid type (exacte interne naam: de
        // runtime-type-info van een handgebouwd object heeft geen super-keten).
        b.setCurrentBlock(catch1);
        catch1.add(0, new IRInstruction.TryCatchRegion(tryBody.label(), tryEnd.label(),
                catch1.label(), "test/Exception", b.currentLocation()));
        b.emitReturn(b.constInt(0));

        b.setCurrentBlock(tryEnd);
        b.emitReturn(b.constInt(2));

        b.endFunction();
        return b.build();
    }

    // -------------------------------------------------------
    // Processen
    // -------------------------------------------------------

    private static Path runtimeSource() {
        final File root = findRepoRoot();
        final Path runtime = new File(root, "runtime/nabu_runtime.c").toPath();
        System.setProperty("nabu.runtime", runtime.toAbsolutePath().toString());
        assertTrue(Files.exists(runtime), "runtime/nabu_runtime.c niet gevonden");
        return runtime;
    }

    private static File findRepoRoot() {
        File root = new File(".").getAbsoluteFile();
        while (root != null && !"nabu".equals(root.getName())) {
            root = root.getParentFile();
        }
        if (root == null) {
            throw new IllegalStateException("repo-root 'nabu' niet gevonden");
        }
        return root;
    }
}

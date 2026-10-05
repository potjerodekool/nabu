package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.backend.CompileOptions;
import io.github.potjerodekool.nabu.backend.ir.CallKind;
import io.github.potjerodekool.nabu.backend.ir.IRBasicBlock;
import io.github.potjerodekool.nabu.backend.ir.IRBuilder;
import io.github.potjerodekool.nabu.backend.ir.IRField;
import io.github.potjerodekool.nabu.backend.ir.IRModule;
import io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;
import io.github.potjerodekool.nabu.lang.Flags;
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
 * BOEHM-e2e: een échte gelinkte en gedraaide native exe.
 *
 * <p>Gelinkt wordt tegen een <em>dubbel</em> van de Boehm-collector, niet tegen
 * een echte libgc: die is niet overal geïnstalleerd en het gedrag dat hier
 * telt zit in de codegeneratie, niet in de collector. De dubbel is daarom
 * strenger dan het origineel:
 * <ul>
 *   <li>{@code GC_malloc} vult met {@code 0xAB} in plaats van nul. De backend
 *       moet dus élk veld dat hij later leest zelf schrijven; een vergissing
 *       leidt hier tot een leeswaarde van 0xAB in plaats van een stille 0.
 *   <li>{@code GC_malloc} vóór {@code GC_init} is een abort. Bewijst dat de
 *       constructor uit {@link BoehmBackendTest} echt vóór main draait.
 *   <li>{@code nabu_test_gc_alloc_count} telt de calls, zodat het programma
 *       kan bewijzen dat álle drie de allocaties door de collector gingen.
 * </ul>
 *
 * <p>Of de echte {@code libgc} in de linkregel staat, controleert
 * {@link #theLinkCommandCarriesBoehm()}.
 *
 * <p>Exit-code 0 = geslaagd; 20..25 geeft de eerste faalstap.
 */
class BoehmRuntimeE2eTest {

    private static final IRType BOX = new IRType.Ptr(IRType.I8, "Ltest/Box;");
    private static final IRType EXCEPTION = new IRType.Ptr(IRType.I8, "Ljava/lang/Exception;");

    @TempDir
    Path tempDir;

    @Test
    void everyAllocationGoesThroughTheCollectorAndTheCollectorIsInitedFirst() throws Exception {
        final String gcc = NativeToolchain.assumeGcc();

        final CompileOptions opts = new CompileOptions(
                CompileOptions.OptLevel.DEFAULT,
                false,
                "x86_64-w64-windows-gnu",
                CompileOptions.GcStrategy.BOEHM,
                JavaVersion.MINIMAL_VERSION);
        final Path obj = tempDir.resolve("boehm-e2e.o");
        new NativeLLVMBackend().compileToObject(module(), opts, obj);

        // Zelf linken i.p.v. via Linker.link(): die voegt -lgc toe en daar is
        // hier geen libgc voor. De dubbel levert GC_malloc en GC_init.
        final Path stub = tempDir.resolve("gc_stub.c");
        Files.writeString(stub, GC_STUB);
        final Path exe = tempDir.resolve("boehm-e2e.exe");
        final List<String> cmd = new ArrayList<>(List.of(
                gcc, obj.toString(), stub.toString(), runtimeSource().toString(),
                "-o", exe.toString()));
        run(cmd);

        final Process proc = NativeToolchain.run(exe).redirectErrorStream(true).start();
        final String output = new String(proc.getInputStream().readAllBytes());
        assertEquals(0, proc.waitFor(), "Expected exit 0. Output:\n" + output);
    }

    /**
     * De {@code -lgc} in de linkregel is wat een echt programma aan de echte
     * collector bindt; zonder die regel zou de backend alleen een {@code .ll} en
     * een {@code .o} opleveren.
     */
    @Test
    void theLinkCommandCarriesBoehm() throws Exception {
        final var objects = List.of(Path.of("test.o"));
        final var exe = Path.of("test.exe");

        assertTrue(Linker.buildCommand(objects, exe, "x86_64-w64-windows-gnu",
                        CompileOptions.GcStrategy.BOEHM).contains("-lgc"),
                "BOEHM moet -lgc krijgen");
        assertTrue(Linker.buildCommand(objects, exe, "x86_64-pc-linux-gnu",
                        CompileOptions.GcStrategy.BOEHM).contains("-lgc"),
                "BOEHM moet -lgc krijgen op Linux");
        for (final var strategy : List.of(CompileOptions.GcStrategy.NONE,
                CompileOptions.GcStrategy.REFCOUNT)) {
            assertTrue(!Linker.buildCommand(objects, exe, "x86_64-w64-windows-gnu",
                    strategy).contains("-lgc"),
                    strategy + " mag geen -lgc krijgen");
        }
    }

    // -------------------------------------------------------
    // Het programma
    // -------------------------------------------------------

    private IRModule module() {
        final IRBuilder b = new IRBuilder("test/Box");
        final IRType i8ptr = new IRType.Ptr(IRType.I8);

        // Test-naam van de GC-dubbel (zie de klasse-javadoc).
        b.declareExternalFunction("nabu_test_gc_inited", IRType.fn(IRType.I32));
        b.declareExternalFunction("nabu_test_gc_alloc_count", IRType.fn(IRType.I64));

        b.beginFunction("main", IRType.I32, List.of(), 0L, false);

        // 1. Een object: veld schrijven en teruglezen.
        final IRValue obj = b.emitHeapAlloc("box", BOX);
        final IRValue x = new IRValue.Values(obj,
                new IRValue.Named("x", IRType.I32, BOX, false, 0));
        b.emitStore(x, b.constInt(42));
        returnIf(b, neq(b, b.emitLoad(x), b.constInt(42)), 20);

        // 2. Een array: schrijven, teruglezen, lengte.
        final IRValue arr = b.emitAllocaArray(3, IRType.I32);
        b.emitArrayStore(arr, b.constInt(0), b.constInt(5), IRType.I32);
        returnIf(b, neq(b, b.emitArrayLoad(arr, b.constInt(0), IRType.I32), b.constInt(5)),
                21);
        returnIf(b, neq(b, b.emitArrayLength(arr), b.constInt(3)), 22);

        // 3. Een onbekende library-klasse: de header moet door de runtime
        //    gezet zijn, anders kan instanceof dit niet herkennen.
        final IRValue exc = b.emitHeapAlloc("exc", EXCEPTION);
        final IRValue isExc = b.emitInstanceOf(exc, EXCEPTION);
        returnIf(b, neq(b, isExc, b.constBool(true)), 23);

        // 4. De collector is vóór main gestart.
        final IRValue inited = call(b, "nabu_test_gc_inited", IRType.I32, List.of(), List.of());
        returnIf(b, neq(b, inited, b.constInt(1)), 24);

        // 5. Alle drie de allocaties gingen door de collector. Zou er één via
        //    malloc zijn gegaan, dan is dit aantal te laag — of zou de exe
        //    helemaal niet gelinkt zijn (GC_malloc zou ontbreken).
        final IRValue allocs = call(b, "nabu_test_gc_alloc_count", IRType.I64,
                List.of(), List.of());
        returnIf(b, lt(b, allocs, b.constInt(3, 64)), 25);

        b.emitReturn(b.constInt(0));
        b.endFunction();

        final IRModule m = b.build();
        m.emitField(IRField.field(Flags.PUBLIC, "x", IRType.I32, null));
        return m;
    }

    // -------------------------------------------------------
    // IR-hulp
    // -------------------------------------------------------

    private static IRValue call(final IRBuilder b, final String fn, final IRType ret,
                                final List<IRType> params, final List<IRValue> args) {
        return b.emitCall(CallKind.STATIC, fn, ret, params, args);
    }

    private static IRValue neq(final IRBuilder b, final IRValue a, final IRValue c) {
        return b.emitBinaryOp(IRInstruction.BinaryOp.Op.NEQ, a, c);
    }

    private static IRValue lt(final IRBuilder b, final IRValue a, final IRValue c) {
        return b.emitBinaryOp(IRInstruction.BinaryOp.Op.LT, a, c);
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

    // -------------------------------------------------------
    // Processen
    // -------------------------------------------------------

    private static Path runtimeSource() {
        final File root = findRepoRoot();
        System.setProperty("nabu.runtime",
                new File(root, "runtime/nabu_runtime.c").getAbsolutePath());
        return Path.of(System.getProperty("nabu.runtime"));
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

    private static void run(final List<String> cmd) throws Exception {
        final Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        final String out = new String(p.getInputStream().readAllBytes());
        assertEquals(0, p.waitFor(), "linken mislukt: " + String.join(" ", cmd) + "\n" + out);
    }

    /**
     * Dubbel van de Boehm-collector. Alleen de twee symbolen die de backend
     * gebruikt, plus twee testers die het programma uitleest.
     */
    private static final String GC_STUB = """
            #include <stddef.h>
            #include <stdlib.h>
            #include <string.h>

            static int  gc_inited = 0;
            static long gc_allocs  = 0;

            void GC_init(void) {
                gc_inited = 1;
            }

            void *GC_malloc(size_t size) {
                void *p;
                if (!gc_inited) {
                    /* Zou een constructor of statische initialisator 'm al gebruiken
                     * voordat GC_init draait, dan is dat een echte bug. */
                    abort();
                }
                p = malloc(size);
                if (p == NULL) {
                    abort();
                }
                /* Gif in plaats van nul: de backend moet elk veld dat hij leest
                 * zelf schrijven. De echte GC_malloc levert wél nulgeheugen. */
                memset(p, 0xAB, size);
                gc_allocs++;
                return p;
            }

            int nabu_test_gc_inited(void) {
                return gc_inited;
            }

            long nabu_test_gc_alloc_count(void) {
                return gc_allocs;
            }
            """;
}

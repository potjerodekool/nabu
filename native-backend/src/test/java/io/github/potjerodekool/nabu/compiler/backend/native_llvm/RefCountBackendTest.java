package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.backend.CompileOptions;
import io.github.potjerodekool.nabu.backend.ir.CallKind;
import io.github.potjerodekool.nabu.backend.ir.IRBuilder;
import io.github.potjerodekool.nabu.backend.ir.IRField;
import io.github.potjerodekool.nabu.backend.ir.IRModule;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;
import io.github.potjerodekool.nabu.lang.Flags;
import io.github.potjerodekool.nabu.tools.JavaVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GcStrategy.REFCOUNT: de semantiek is "sterke eigenaar per toewijzing".
 *
 * <pre>
 *   alloc                -> nabu_retain   (de frame wordt eigenaar)
 *   slot = v             -> nabu_release  (wat erin zat gaat dood) + eigendom gaat over
 *   exit-pad van de frame-> nabu_release  (wat de frame nog bezit)
 * </pre>
 *
 * De LLVMVerifyModule in {@code NativeLLVMBackend} bewijst dat de IR klopt;
 * de runtime-e2e in {@link RefCountE2eTest} bewijst dat de telling klopt.
 */
class RefCountBackendTest {

    private static final IRType BOX = new IRType.Ptr(IRType.I8, "Ltest/Box;");

    @TempDir
    Path tempDir;

    private static CompileOptions refCount() {
        return new CompileOptions(CompileOptions.OptLevel.NONE, false, null,
                CompileOptions.GcStrategy.REFCOUNT, JavaVersion.MINIMAL_VERSION);
    }

    private String emit(IRModule module, String name) throws Exception {
        final var ll = tempDir.resolve(name + ".ll");
        new NativeLLVMBackend().emitIRText(module, refCount(), ll);
        NativeToolchain.dump(ll, name + ".ll");
        return Files.readString(ll);
    }

    // -------------------------------------------------------
    // Retain bij allocatie, release in de exit-pad
    // -------------------------------------------------------

    @Test
    void allocationIsRetainedAndFreedByTheExitPad() throws Exception {
        final var b = new IRBuilder("test/Box");
        b.beginFunction("main", IRType.I32, List.of(), false);

        final var obj = b.emitHeapAlloc("obj", BOX);
        b.emitStore(field(obj, "x"), b.constInt(1));
        b.emitReturn(b.constInt(0));
        b.endFunction();

        final var module = b.build();
        module.emitField(IRField.field(Flags.PUBLIC, "x", IRType.I32, null));

        final var ll = emit(module, "rc_retain");

        assertTrue(ll.contains("call void @nabu_retain"),
                "Geen retain na de allocatie:\n" + ll);
        assertTrue(ll.contains("nabu.rc.exit:"),
                "Geen exit-pad:\n" + ll);
        assertTrue(ll.contains("call void @nabu_release"),
                "Geen release in de exit-pad:\n" + ll);

        // Precies één retain en één release: de allocatie is frame-eigen.
        assertEquals(1, count(ll, "call void @nabu_retain"), ll);
        assertEquals(1, count(ll, "call void @nabu_release"), ll);

        // De release staat achter de ret (de exit-pad is het laatste blok).
        assertTrue(ll.indexOf("call void @nabu_retain") < ll.indexOf("nabu.rc.exit:"), ll);

        // De teller moet op 0 gezet worden: malloc geeft ongeïnitialiseerd
        // geheugen terug, en zonder nulstore komt geen object ooit vrij.
        assertTrue(ll.contains(".refcount.slot"), ll);
        assertTrue(ll.contains("store i32 0, ptr %.refcount.slot"), ll);
    }

    // -------------------------------------------------------
    // Ontsnapping via een call-argument
    // -------------------------------------------------------

    @Test
    void anObjectHandedToAnUnknownCallIsTreatedAsEscaped() throws Exception {
        final var b = new IRBuilder("test/Box");
        b.declareExternalFunction("useIt", IRType.fn(IRType.VOID, new IRType.Ptr(IRType.I8)));

        b.beginFunction("main", IRType.I32, List.of(), false);
        // var x = new Box();  useIt(x);
        b.emitCall(CallKind.STATIC, "useIt", IRType.VOID,
                List.of(new IRType.Ptr(IRType.I8)),
                List.of(b.emitHeapAlloc("obj", BOX)));
        b.emitReturn(b.constInt(0));
        b.endFunction();

        final var module = b.build();
        module.emitField(IRField.field(Flags.PUBLIC, "x", IRType.I32, null));
        final var ll = emit(module, "rc_escape_call");

        // De backend weet niet wat de callee met de pointer doet, dus geen
        // retain (en dus ook geen release): het object leekt. Bewust zo —
        // vrijgeven terwijl de callee het vasthoudt is erger.
        assertFalse(ll.contains("call void @nabu_retain"), ll);
        assertFalse(ll.contains("call void @nabu_release"), ll);
    }

    // -------------------------------------------------------
    // De toewijzing die vrijkomt
    // -------------------------------------------------------

    @Test
    void overwritingAReferenceSlotReleasesTheValueThatDies() throws Exception {
        final var b = new IRBuilder("test/Box");
        b.beginFunction("main", IRType.I32, List.of(), false);

        // var s: Box = new Box();  s = new Box();
        final var slot = b.emitAlloca("s", BOX);
        b.emitStore(slot, b.emitHeapAlloc("a", BOX));
        b.emitStore(slot, b.emitHeapAlloc("b2", BOX));
        b.emitReturn(b.constInt(0));
        b.endFunction();

        final var ll = emit(b.build(), "rc_overwrite");

        // Twee allocs -> twee retains.
        assertEquals(2, count(ll, "call void @nabu_retain"), ll);

        // Drie release-aanroepen:
        //  - bij de eerste overschrijving staat nog niets in de slot: de
        //    identiteits-guard levert dus null op (een no-op in de runtime),
        //  - bij de tweede overschrijving wordt 'a' vrijgegeven,
        //  - in de exit-pad wordt 'b2' uit de slot vrijgegeven.
        assertEquals(3, count(ll, "call void @nabu_release"), ll);

        // Beide releases lopen via een identiteits-guard, dus er kan nooit een
        // vreemde pointer worden vrijgegeven.
        assertTrue(ll.contains("icmp eq ptr"), ll);
        assertTrue(ll.contains("nabu.rc.overwrite."), ll);
        assertTrue(ll.contains("nabu.rc.sweep."), ll);
    }

    // -------------------------------------------------------
    // Escapende waarden
    // -------------------------------------------------------

    @Test
    void returnedObjectIsNotReleasedByTheFrameThatAllocatedIt() throws Exception {
        final var b = new IRBuilder("test/Box");

        // makeBox(): Box = new Box(); return box;  -> de alloc ontsnapt.
        b.beginFunction("makeBox", BOX, List.of(), false);
        b.emitReturn(b.emitHeapAlloc("made", BOX));
        b.endFunction();

        // main(): Box x = makeBox(); return 0;
        b.beginFunction("main", IRType.I32, List.of(), false);
        final var slot = b.emitAlloca("x", BOX);
        b.declareExternalFunction("makeBox", IRType.fn(BOX));
        b.emitStore(slot, b.emitCall(CallKind.STATIC,
                "makeBox", BOX, List.of(), List.of()));
        b.emitReturn(b.constInt(0));
        b.endFunction();

        final var module = b.build();
        module.emitField(IRField.field(Flags.PUBLIC, "x", IRType.I32, null));
        final var ll = emit(module, "rc_escape");

        // makeBox's alloc ontsnapt: geen retain en geen release in makeBox.
        final var makeBox = between(ll, "define ptr @makeBox", "nabu.rc.exit:");
        assertFalse(makeBox.contains("nabu_retain"),
                "Ontsnappende allocatie mag geen retain krijgen:\n" + makeBox);
        assertFalse(makeBox.contains("nabu_release"),
                "Ontsnappende allocatie mag niet worden vrijgegeven:\n" + makeBox);

        // In main is er geen allocatie van ons, dus ook geen release.
        final var main = between(ll, "define i32 @main", null);
        assertFalse(main.contains("nabu_retain"), main);
    }

    // -------------------------------------------------------
    // Terughoudendheid
    // -------------------------------------------------------

    @Test
    void theOtherGcStrategiesEmitNoRefCountCalls() throws Exception {
        final var b = new IRBuilder("test/Box");
        b.beginFunction("main", IRType.I32, List.of(), false);
        final var slot = b.emitAlloca("s", BOX);
        b.emitStore(slot, b.emitHeapAlloc("a", BOX));
        b.emitReturn(b.constInt(0));
        b.endFunction();
        final var module = b.build();
        module.emitField(IRField.field(Flags.PUBLIC, "x", IRType.I32, null));

        for (final var strategy : List.of(CompileOptions.GcStrategy.NONE,
                CompileOptions.GcStrategy.BOEHM)) {
            final var ll = tempDir.resolve("rc_" + strategy + ".ll");
            new NativeLLVMBackend().emitIRText(module,
                    new CompileOptions(CompileOptions.OptLevel.NONE, false, null, strategy,
                            JavaVersion.MINIMAL_VERSION), ll);
            final var text = Files.readString(ll);
            assertFalse(text.contains("nabu_retain"),
                    "Onterechte retain bij strategie " + strategy + ":\n" + text);
            assertFalse(text.contains("nabu_release"), text);
        }
    }

    @Test
    void aLoopReassigningAReferenceSlotIsRejectedWithAClearDiagnostic() {
        final var b = new IRBuilder("test/Box");
        b.beginFunction("main", IRType.I32, List.of(), false);
        final var slot = b.emitAlloca("s", BOX);
        b.emitStore(slot, b.emitHeapAlloc("a", BOX));

        final var head = b.currentBlock();
        final var body = b.beginBlock("body");
        final var exit = b.beginBlock("exit");

        b.setCurrentBlock(head);
        b.emitCondBranch(b.constInt(0), body, exit);

        b.setCurrentBlock(body);
        // Tweede beschrijving van dezelfde referentie-slot, in een lus.
        b.emitStore(slot, b.emitHeapAlloc("b2", BOX));
        b.emitBranch(head);

        b.setCurrentBlock(exit);
        b.emitReturn(b.constInt(0));
        b.endFunction();

        final var ll = tempDir.resolve("rc_loop.ll");
        final var error = assertThrows(IllegalStateException.class,
                () -> new NativeLLVMBackend().emitIRText(b.build(), refCount(), ll));
        assertTrue(error.getMessage().contains("REFCOUNT"),
                "Diagnose moet de strategie noemen: " + error.getMessage());
        assertTrue(error.getMessage().contains("main"),
                "Diagnose moet de functie noemen: " + error.getMessage());
    }

    // -------------------------------------------------------
    // Hulp
    // -------------------------------------------------------

    private static IRValue field(IRValue obj, String name) {
        return new IRValue.Values(obj, new IRValue.Named(name, IRType.I32, BOX, false, 0));
    }

    private static int count(String text, String needle) {
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }

    /** Deel van {@code text} vanaf {@code from} tot {@code to} (of het einde). */
    private static String between(String text, String from, String to) {
        final int start = text.indexOf(from);
        if (start < 0) return "";
        final int end = to == null ? -1 : text.indexOf(to, start);
        return end < 0 ? text.substring(start) : text.substring(start, end);
    }
}

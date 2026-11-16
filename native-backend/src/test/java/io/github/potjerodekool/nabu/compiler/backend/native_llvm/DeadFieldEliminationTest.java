package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.backend.CompileOptions;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A field the program never reads is left out of the object struct: a store to it becomes a no-op, so no GEP for it
 * appears in the IR. Public and volatile fields keep their slot, and {@code -Dnabu.deadFields=false} switches the
 * whole pass off.
 */
class DeadFieldEliminationTest {

    @TempDir
    Path tempDir;

    private static final IRType BOX = new IRType.Ptr(IRType.I8, "Ltest/Box;");

    /**
     * test/Box { int read; int written; int volatile; int visible; } — {@code read} is loaded, {@code written} only
     * stored, the last two differ per test.
     */
    private IRModule box(final long writtenFlags, final long visibleFlags) {
        final IRBuilder b = new IRBuilder("test/Box");
        b.beginFunction("main", IRType.I32, List.of(), false);
        final var obj = b.emitHeapAlloc("box", BOX);

        final var read = new IRValue.Named("read", IRType.I32, BOX, false, 0);
        b.emitStore(new IRValue.Values(obj, read), b.constInt(3));

        final var written = new IRValue.Named("written", IRType.I32, BOX, false, 1);
        b.emitStore(new IRValue.Values(obj, written), b.constInt(7));

        final var volatileField = new IRValue.Named("volatile", IRType.I32, BOX, false, 2);
        b.emitStore(new IRValue.Values(obj, volatileField), b.constInt(11));

        final var visible = new IRValue.Named("visible", IRType.I32, BOX, false, 3);
        b.emitStore(new IRValue.Values(obj, visible), b.constInt(13));

        b.emitReturn(b.emitLoad(new IRValue.Values(obj, read)));
        b.endFunction();

        final IRModule m = b.build();
        m.emitField(IRField.field(Flags.PRIVATE, "read", IRType.I32, null));
        m.emitField(IRField.field(writtenFlags, "written", IRType.I32, null));
        m.emitField(IRField.field(Flags.PRIVATE | Flags.VOLATILE, "volatile", IRType.I32, null));
        m.emitField(IRField.field(visibleFlags, "visible", IRType.I32, null));
        return m;
    }

    private String ll(final IRModule module) throws Exception {
        final Path out = tempDir.resolve("out.o");
        new NativeLLVMBackend().compileToObject(module,
                new CompileOptions(CompileOptions.OptLevel.NONE, false, null, CompileOptions.GcStrategy.BOEHM,
                        JavaVersion.MINIMAL_VERSION), out);
        return Files.readString(tempDir.resolve("out.ll"));
    }

    @Test
    void neverReadFieldLeavesTheStruct() throws Exception {
        final String ll = ll(box(Flags.PRIVATE, Flags.PRIVATE));
        assertTrue(ll.contains(".field.read"), "A read field keeps its slot:\n" + ll);
        assertFalse(ll.contains(".field.written"), "A write-only field is left out:\n" + ll);
    }

    @Test
    void volatileAndPublicFieldsAreKept() throws Exception {
        final String ll = ll(box(Flags.PRIVATE, Flags.PUBLIC));
        assertTrue(ll.contains(".field.volatile"), "A volatile field keeps its slot:\n" + ll);
        assertTrue(ll.contains(".field.visible"), "A public field keeps its slot:\n" + ll);
    }

    @Test
    void eliminationCanBeSwitchedOff() throws Exception {
        System.setProperty("nabu.deadFields", "false");
        try {
            final String ll = ll(box(Flags.PRIVATE, Flags.PRIVATE));
            assertTrue(ll.contains(".field.written"), "-Dnabu.deadFields=false keeps every field:\n" + ll);
        } finally {
            System.clearProperty("nabu.deadFields");
        }
    }
}

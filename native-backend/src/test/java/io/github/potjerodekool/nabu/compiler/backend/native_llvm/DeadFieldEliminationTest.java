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

    /** A class whose only use of the field is a store: no compiled code reads it. */
    private IRModule writeOnly(final String internalName, final String field) {
        final IRBuilder b = new IRBuilder(internalName);
        final IRType type = new IRType.Ptr(IRType.I8, "L" + internalName + ";");
        b.beginFunction("main", IRType.I32, List.of(), false);
        final var obj = b.emitHeapAlloc("object", type);
        final var named = new IRValue.Named(field, IRType.I32, type, false, 0);
        b.emitStore(new IRValue.Values(obj, named), b.constInt(5));
        b.emitReturn(b.constInt(0));
        b.endFunction();
        final IRModule m = b.build();
        m.emitField(IRField.field(Flags.PRIVATE, field, IRType.I32, null));
        return m;
    }

    /**
     * The fields that the runtime reads by name stay in the struct, also when nothing else reads them. From JDK
     * 25.0.5 Reference.get() calls the native get0() and nothing in the program read Reference.referent any more:
     * it was left out, WeakReference.get() returned null, and with it every ThreadLocal of a thread that is not the
     * main thread.
     */
    @Test
    void theFieldsTheRuntimeReadsByNameStay() throws Exception {
        for (final var entry : ClassLayouts.READ_BY_THE_RUNTIME.entrySet()) {
            for (final String field : entry.getValue()) {
                final String ll = ll(writeOnly(entry.getKey(), field));
                assertTrue(ll.contains(".field." + field), entry.getKey() + "." + field + " keeps its slot:\n" + ll);
            }
        }
    }

    /** Whatever jcl_lite.c asks nabu_instance_field_offset for is in ClassLayouts.READ_BY_THE_RUNTIME. */
    @Test
    void everyFieldNameOfTheRuntimeIsInTheTable() throws Exception {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.isRegularFile(dir.resolve("runtime/jcl_lite.c"))) {
            dir = dir.getParent();
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(dir != null, "runtime/jcl_lite.c not found above the test directory");
        final String source = Files.readString(dir.resolve("runtime/jcl_lite.c"), java.nio.charset.StandardCharsets.UTF_8);
        final var matcher = java.util.regex.Pattern.compile("nabu_instance_field_offset\\([^,]+,\\s*\"([A-Za-z0-9_]+)\"\\)")
                .matcher(source);
        final java.util.Set<String> asked = new java.util.TreeSet<>();
        while (matcher.find()) {
            asked.add(matcher.group(1));
        }
        assertFalse(asked.isEmpty(), "no nabu_instance_field_offset call found: the pattern does not match any more");
        final java.util.Set<String> known = new java.util.HashSet<>();
        ClassLayouts.READ_BY_THE_RUNTIME.values().forEach(known::addAll);
        final java.util.Set<String> missing = new java.util.TreeSet<>(asked);
        missing.removeAll(known);
        assertTrue(missing.isEmpty(),
                "jcl_lite.c reads these fields by name, add them to ClassLayouts.READ_BY_THE_RUNTIME: " + missing);
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

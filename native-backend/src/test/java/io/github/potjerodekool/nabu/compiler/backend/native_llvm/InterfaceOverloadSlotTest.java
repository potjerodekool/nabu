package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.backend.CompileOptions;
import io.github.potjerodekool.nabu.backend.ir.CallKind;
import io.github.potjerodekool.nabu.backend.ir.IRBuilder;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;
import io.github.potjerodekool.nabu.lang.Flags;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Overloads van een interface-methode krijgen elk een eigen itable-slot met
 * de bijpassende implementatie. Op naam alleen deelden
 * {@code Appendable.append(char)} en {@code append(CharSequence)} één slot,
 * gevuld met append(char): Formatter schreef daardoor onzin-tekst.
 */
class InterfaceOverloadSlotTest {

    @TempDir
    Path tempDir;

    private static final IRType SINK = new IRType.Ptr(IRType.I8, "Ltest/Sink;");
    private static final IRType BUF = new IRType.Ptr(IRType.I8, "Ltest/Buf;");
    private static final IRType TEXT = new IRType.Ptr(IRType.I8, "Ljava/lang/CharSequence;");

    @Test
    void overloadsGetTheirOwnSlotAndImplementation() throws Exception {
        IRBuilder sink = new IRBuilder(Flags.INTERFACE, "test.Sink");
        sink.superType(new IRType.Ptr(IRType.I8, "Ljava/lang/Object;"));
        // Alleen de char-overload gedeclareerd, zoals bij een gelowerde
        // JDK-interface waarvan niet elke overload is aangeroepen.
        sink.beginFunction("Sink_add", SINK, List.of(new IRValue.Temp("%this", SINK),
                new IRValue.Temp("%c", IRType.I16)), Flags.ABSTRACT);
        sink.endFunction();

        IRBuilder buf = new IRBuilder("test.Buf");
        buf.superType(new IRType.Ptr(IRType.I8, "Ljava/lang/Object;"));
        buf.interfaces(List.of(SINK));
        buf.beginFunction("Buf_add", BUF, List.of(new IRValue.Temp("%this", BUF),
                new IRValue.Temp("%c", IRType.I16)), 0L);
        buf.emitReturn(new IRValue.Temp("%this", BUF));
        buf.endFunction();
        buf.beginFunction("Buf_add", BUF, List.of(new IRValue.Temp("%this", BUF),
                new IRValue.Temp("%s", TEXT)), 0L);
        buf.emitReturn(new IRValue.Temp("%this", BUF));
        buf.endFunction();

        IRBuilder main = new IRBuilder("test.Main");
        var s = new IRValue.Temp("s", SINK);
        var t = new IRValue.Temp("t", TEXT);
        main.beginFunction("Main_run", IRType.VOID, List.of(s, t), Flags.STATIC, false);
        main.emitCall(CallKind.INTERFACE, "test.Sink_add", SINK, List.of(IRType.I16), List.of(s, main.constInt(65)));
        main.emitCall(CallKind.INTERFACE, "test.Sink_add", SINK, List.of(TEXT), List.of(s, t));
        main.emitReturn(null);
        main.endFunction();

        Path out = tempDir.resolve("out.o");
        new NativeLLVMBackend().compileToObject(List.of(sink.build(), buf.build(), main.build()),
                new CompileOptions(CompileOptions.OptLevel.NONE, false, null,
                        CompileOptions.GcStrategy.BOEHM,
                        io.github.potjerodekool.nabu.tools.JavaVersion.MINIMAL_VERSION), out);
        String ll = Files.readString(tempDir.resolve("out.ll"));

        String run = ll.substring(ll.indexOf("define void @Main_run("));
        run = run.substring(0, run.indexOf("\n}"));
        Matcher slots = Pattern.compile("getelementptr ptr, ptr %[.]itable[0-9]*, i64 ([0-9]+)").matcher(run);
        assertTrue(slots.find(), run);
        final int charSlot = Integer.parseInt(slots.group(1));
        assertTrue(slots.find(), run);
        final int textSlot = Integer.parseInt(slots.group(1));
        assertTrue(charSlot != textSlot, "elke overload een eigen slot:\n" + run);

        String itable = ll.lines().filter(l -> l.startsWith("@_ZNabutest_BufEitable ="))
                .findFirst().orElseThrow();
        String[] entries = itable.substring(itable.indexOf('[', itable.indexOf("] ") + 1) + 1).split(", ");
        // De eerste overload (char) houdt de kale naam, de tweede krijgt een
        // parameter-suffix (FnKeys.stableSymbol).
        assertTrue(entries[charSlot].trim().equals("ptr @Buf_add"),
                "char-slot: " + entries[charSlot] + "\n" + itable);
        assertTrue(entries[textSlot].contains("Buf_add$P"),
                "CharSequence-slot: " + entries[textSlot] + "\n" + itable);
        assertEquals(2, java.util.Arrays.stream(entries).filter(e -> e.contains("Buf_add")).count(), itable);
    }
}

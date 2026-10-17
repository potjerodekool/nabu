package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.backend.CompileOptions;
import io.github.potjerodekool.nabu.backend.ir.CallKind;
import io.github.potjerodekool.nabu.backend.ir.IRBuilder;
import io.github.potjerodekool.nabu.backend.ir.IRModule;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Overloads die alleen in een referentie-parametertype verschillen hebben
 * ieder een eigen vtable-slot, en een virtuele call vindt het slot van de
 * overload met dát parametertype.
 *
 * <p>picocli's {@code ArgSpec.Builder.arity(String)} roept
 * {@code arity(Range.valueOf(range))} aan. De slot-sleutel telde elke
 * referentieparameter als kale {@code P}; beide overloads kregen
 * {@code Builder_arity#PP}, en de call naar {@code arity(Range)} belandde in
 * het slot van {@code arity(String)} — dat zichzelf daarmee opnieuw aanriep met
 * een Range als String.
 */
class ReferenceOverloadSlotTest {

    @TempDir
    Path tempDir;

    private static final IRType BUILDER = new IRType.Ptr(IRType.I8, "Ltest/Builder;");
    private static final IRType STRING = new IRType.Ptr(IRType.I8, "Ljava/lang/String;");
    private static final IRType RANGE = new IRType.Ptr(IRType.I8, "Ltest/Range;");

    @Test
    void virtualCallUsesTheSlotOfTheMatchingReferenceOverload() throws Exception {
        IRBuilder b = new IRBuilder("test/Builder");
        b.superType(new IRType.Ptr(IRType.I8, "Ljava/lang/Object;"));
        var self = new IRValue.Temp("this", BUILDER);

        b.beginFunction("Builder_arity", BUILDER,
                List.of(self, new IRValue.Temp("range", STRING)), 0L);
        b.emitReturn(self);
        b.endFunction();

        b.beginFunction("Builder_arity", BUILDER,
                List.of(self, new IRValue.Temp("arity", RANGE)), 0L);
        b.emitReturn(self);
        b.endFunction();

        b.beginFunction("Builder_run", BUILDER, List.of(self), 0L);
        var result = b.emitCall(CallKind.VIRTUAL, "Builder_arity", BUILDER,
                List.of(RANGE), List.of(self, new IRValue.ConstNull(RANGE)));
        b.emitReturn(result);
        b.endFunction();

        String ll = compile(List.of(b.build()));

        // De vtable: de Range-overload draagt de descriptor-hash in haar symbool.
        String vtableLine = Arrays.stream(ll.split("\n"))
                .filter(l -> l.startsWith("@_ZNabutest_BuilderEvtable ="))
                .findFirst().orElseThrow(() -> new AssertionError("geen vtable:\n" + ll));
        List<String> entries = Arrays.stream(vtableLine.substring(vtableLine.indexOf('[', vtableLine.indexOf('=')) + 1)
                        .split(",")).map(String::trim).toList();
        String rangeHash = Integer.toHexString("Ltest/Range;".hashCode());
        int rangeSlot = -1;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).contains("Builder_arity") && entries.get(i).contains(rangeHash)) rangeSlot = i;
        }
        assertTrue(rangeSlot >= 0, "Range-overload niet in de vtable:\n" + vtableLine);

        // De call in Builder_run indexeert de vtable op dat slot.
        String run = ll.substring(ll.indexOf("define ptr @Builder_run("));
        run = run.substring(0, run.indexOf("\n}"));
        var gep = Pattern.compile("getelementptr ptr, ptr %[^,]+, i64 (\\d+)").matcher(run);
        assertTrue(gep.find(), "geen vtable-dispatch in Builder_run:\n" + run);
        assertEquals(rangeSlot, Integer.parseInt(gep.group(1)),
                "arity(Range) moet via het slot van de Range-overload:\n" + vtableLine + "\n" + run);
        // Een null-ontvanger gooit NullPointerException i.p.v. adres 0 te lezen.
        assertTrue(run.contains("call void @nabu_throw_npe("),
                "virtuele dispatch zonder null-check:\n" + run);
    }

    private String compile(List<IRModule> modules) throws Exception {
        Path out = tempDir.resolve("out.o");
        new NativeLLVMBackend().compileToObject(modules,
                new CompileOptions(CompileOptions.OptLevel.NONE, false, null,
                        CompileOptions.GcStrategy.BOEHM,
                        io.github.potjerodekool.nabu.tools.JavaVersion.MINIMAL_VERSION), out);
        return Files.readString(tempDir.resolve("out.ll"));
    }
}

package io.github.potjerodekool.nabu.compiler.ir;

import io.github.potjerodekool.nabu.backend.ir.IRBuilder;
import io.github.potjerodekool.nabu.backend.ir.dataflow.analysis.Nullness;
import io.github.potjerodekool.nabu.backend.ir.dataflow.analysis.NullnessFlow;
import io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The dense analysis gives what the generic one gives (NullnessAnalysis through the engine). */
class NullnessFlowTest {

    private static final IRType PTR = new IRType.Ptr(IRType.I32);

    private static String name(IRValue v) {
        return ((IRValue.Temp) v).name();
    }

    @Test
    void diamondWithPhiAndAValueFromOneSideOnly() {
        final var builder = new IRBuilder("test");
        builder.beginFunction("f", IRType.VOID, List.of(), 0);
        final var entry = builder.currentBlock();
        final var a = builder.emitHeapAlloc("a", new IRType.Struct(List.of()));
        final var cond = builder.constBool(true);
        final var t = builder.beginBlock("t");
        final var e = builder.beginBlock("e");
        builder.setCurrentBlock(entry);
        builder.emitCondBranch(cond, t, e);
        final var m = builder.beginBlock("m");
        builder.setCurrentBlock(t);
        final var onlyT = builder.emitHeapAlloc("onlyT", new IRType.Struct(List.of()));
        builder.emitBranch(m);
        builder.setCurrentBlock(e);
        builder.emitBranch(m);
        builder.setCurrentBlock(m);
        final var phi = builder.emitPhi(PTR, List.of(
                new IRInstruction.Phi.Incoming(a, t),
                new IRInstruction.Phi.Incoming(a, e)));
        builder.emitReturn(null);
        builder.endFunction();
        final var fn = builder.build().functions().getFirst();

        final var result = NullnessFlow.analyze(fn);
        assertNull(NullnessFlow.compareWithReference(fn, result));
        assertEquals(Nullness.NON_NULL, result.inAt(t.label(), name(a)));
        assertEquals(Nullness.NON_NULL, result.inAt(m.label(), name(a)));
        // defined on one side of the merge only: UNKNOWN
        assertEquals(Nullness.UNKNOWN, result.inAt(m.label(), name(onlyT)));
        // nothing is known about a name that nothing defines
        assertNull(result.inAt(m.label(), "nobody"));
        assertNull(result.inAt("nolabel", name(a)));
    }

    @Test
    void loopWithPhi() {
        final var builder = new IRBuilder("test");
        builder.beginFunction("f", IRType.VOID, List.of(), 0);
        final var entry = builder.currentBlock();
        final var a = builder.emitHeapAlloc("a", new IRType.Struct(List.of()));
        final var head = builder.beginBlock("head");
        final var body = builder.beginBlock("body");
        final var exit = builder.beginBlock("exit");
        builder.setCurrentBlock(entry);
        builder.emitBranch(head);
        builder.setCurrentBlock(head);
        final var phi = builder.emitPhi(PTR, List.of(
                new IRInstruction.Phi.Incoming(a, entry),
                new IRInstruction.Phi.Incoming(a, body)));
        builder.emitCondBranch(builder.constBool(true), body, exit);
        builder.setCurrentBlock(body);
        builder.emitBranch(head);
        builder.setCurrentBlock(exit);
        builder.emitReturn(null);
        builder.endFunction();
        final var fn = builder.build().functions().getFirst();

        final var result = NullnessFlow.analyze(fn);
        assertNull(NullnessFlow.compareWithReference(fn, result));
        // The reference analysis gives UNKNOWN behind a loop (the merge with the back edge starts from an empty state
        // and the fixpoint does not recover); the dense one is the same on purpose, the compare above proves it.
        assertEquals(Nullness.UNKNOWN, result.inAt(exit.label(), name(a)));
    }
}

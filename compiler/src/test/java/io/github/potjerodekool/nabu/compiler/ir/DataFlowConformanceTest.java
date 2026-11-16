package io.github.potjerodekool.nabu.compiler.ir;

import io.github.potjerodekool.nabu.backend.ir.IRBasicBlock;
import io.github.potjerodekool.nabu.backend.ir.IRBuilder;
import io.github.potjerodekool.nabu.backend.ir.IRFunction;
import io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.backend.ir.dataflow.DataFlowEngine;
import io.github.potjerodekool.nabu.backend.ir.dataflow.DataFlowResult;
import io.github.potjerodekool.nabu.backend.ir.dataflow.analysis.Interval;
import io.github.potjerodekool.nabu.backend.ir.dataflow.analysis.IntervalAnalysis;
import io.github.potjerodekool.nabu.backend.ir.dataflow.analysis.Nullness;
import io.github.potjerodekool.nabu.backend.ir.dataflow.analysis.NullnessAnalysis;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DataFlowConformanceTest {

    private static IRType PTR = new IRType.Ptr(IRType.I32);

    @Test
    void nullnessPhiAllNonNull() {
        final var builder = new IRBuilder("test");
        builder.beginFunction("f", IRType.VOID, List.of(), 0);
        final var entry = builder.currentBlock();
        final var a = builder.emitHeapAlloc("a", new IRType.Struct(List.of()));
        final var b = builder.emitHeapAlloc("b", new IRType.Struct(List.of()));
        final var cond = builder.constBool(true);
        final var t = builder.beginBlock("t");
        final var e = builder.beginBlock("e");
        builder.setCurrentBlock(entry);
        builder.emitCondBranch(cond, t, e);
        final var m = builder.beginBlock("m");
        builder.setCurrentBlock(t);
        builder.emitBranch(m);
        builder.setCurrentBlock(e);
        builder.emitBranch(m);
        builder.setCurrentBlock(m);
        final var phi = builder.emitPhi(PTR, List.of(
                new IRInstruction.Phi.Incoming(a, t),
                new IRInstruction.Phi.Incoming(b, e)));
        builder.emitReturn(null);
        builder.endFunction();
        final var fn = builder.build().functions().getFirst();

        final var res = DataFlowEngine.analyze(fn, new NullnessAnalysis());
        final Map<String, Nullness> mOut = res.out(m);
        assertTrue(phi instanceof IRValue.Temp);
        String name = ((IRValue.Temp) phi).name();
        assertEquals(Nullness.NON_NULL, mOut.get(name));
    }

    @Test
    void intervalAddition() {
        final var builder = new IRBuilder("test");
        builder.beginFunction("f", IRType.VOID, List.of(), 0);
        final var entry = builder.currentBlock();
        final var ld = builder.constInt(5);
        final var add = builder.emitBinaryOp(IRInstruction.BinaryOp.Op.ADD, ld, builder.constInt(3));
        builder.emitReturn(null);
        builder.endFunction();
        final var fn = builder.build().functions().getFirst();

        final var res = DataFlowEngine.analyze(fn, new IntervalAnalysis());
        final Map<String, Interval> out = res.out(entry);
        Interval iv = out.get(((IRValue.Temp) add).name());
        assertNotNull(iv);
        assertEquals(8, iv.lo());
        assertEquals(8, iv.hi());
    }
}
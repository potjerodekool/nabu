package io.github.potjerodekool.nabu.compiler.ir;

import io.github.potjerodekool.nabu.backend.ir.IRBuilder;
import io.github.potjerodekool.nabu.backend.ir.IRFunction;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import io.github.potjerodekool.nabu.backend.ir.dataflow.DataFlowEngine;
import io.github.potjerodekool.nabu.backend.ir.dataflow.DataFlowResult;
import io.github.potjerodekool.nabu.backend.ir.dataflow.Dominance;
import io.github.potjerodekool.nabu.backend.ir.dataflow.PostDominators;
import io.github.potjerodekool.nabu.backend.ir.dataflow.analysis.LiveVariables;
import io.github.potjerodekool.nabu.backend.ir.dataflow.analysis.ReachingDefinitions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DataFlowEngineTest {

    private IRFunction diamondFunction() {
        final var builder = new IRBuilder("test");
        builder.beginFunction("diamond", IRType.VOID, List.of(), 0);

        final var entry = builder.currentBlock();
        final var cond = builder.constBool(true);
        final var thenBlk = builder.beginBlock("then");
        final var elseBlk = builder.beginBlock("else");

        builder.setCurrentBlock(entry);
        builder.emitCondBranch(cond, thenBlk, elseBlk);

        final var merge = builder.beginBlock("merge");
        builder.setCurrentBlock(thenBlk);
        builder.emitBranch(merge);
        builder.setCurrentBlock(elseBlk);
        builder.emitBranch(merge);

        builder.setCurrentBlock(merge);
        builder.emitReturn(null);
        builder.endFunction();

        return builder.build().functions().getFirst();
    }

    @Test
    void dominanceAnalysisWorks() {
        final var fn = diamondFunction();
        final var dom = Dominance.analyze(fn);
        assertTrue(dom.dominates("entry", "merge"));
        assertFalse(dom.dominates("then", "merge"));
        assertNotNull(dom.postDominators());
    }

    @Test
    void liveVariablesRuns() {
        final var builder = new IRBuilder("test");
        builder.beginFunction("lv", IRType.VOID, List.of(), 0);
        final var entry = builder.currentBlock();
        final var t = builder.emitAlloca("x", IRType.I32);
        builder.emitStore(t, builder.constInt(5));
        builder.emitReturn(null);
        builder.endFunction();
        final var fn = builder.build().functions().getFirst();

        final var res = DataFlowEngine.analyze(fn, new LiveVariables());
        assertNotNull(res);
        assertTrue(res.in(fn.entryBlock()) instanceof Set);
    }

    @Test
    void reachingDefinitionsRuns() {
        final var builder = new IRBuilder("test");
        builder.beginFunction("rd", IRType.VOID, List.of(), 0);
        final var entry = builder.currentBlock();
        final var t = builder.emitAlloca("x", IRType.I32);
        builder.emitStore(t, builder.constInt(5));
        builder.emitReturn(null);
        builder.endFunction();
        final var fn = builder.build().functions().getFirst();

        final var res = DataFlowEngine.analyze(fn, new ReachingDefinitions(fn));
        assertNotNull(res);
    }

    @Test
    void postDominatorsComputed() {
        final var fn = diamondFunction();
        final var pd = PostDominators.compute(fn);
        assertNotNull(pd.getPreorder());
    }
}
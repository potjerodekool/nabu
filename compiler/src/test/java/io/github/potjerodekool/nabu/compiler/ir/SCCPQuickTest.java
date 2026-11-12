package io.github.potjerodekool.nabu.compiler.ir;

import io.github.potjerodekool.nabu.backend.ir.IRBuilder;
import io.github.potjerodekool.nabu.backend.ir.IRFunction;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import io.github.potjerodekool.nabu.backend.ir.dataflow.analysis.SparseConditionalConstantPropagation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SCCPQuickTest {
    @Test
    void sccpConstantBranch() {
        final var builder = new IRBuilder("test");
        builder.beginFunction("f", IRType.VOID, List.of(), 0);
        final var entry = builder.currentBlock();
        final var cond = builder.constBool(true);
        final var thenBlk = builder.beginBlock("then");
        final var elseBlk = builder.beginBlock("else");
        builder.setCurrentBlock(entry);
        builder.emitCondBranch(cond, thenBlk, elseBlk);
        builder.setCurrentBlock(thenBlk);
        builder.emitReturn(null);
        builder.setCurrentBlock(elseBlk);
        builder.emitReturn(null);
        builder.endFunction();
        final var fn = builder.build().functions().getFirst();

        final var res = SparseConditionalConstantPropagation.analyze(fn);
        assertTrue(res.isExecutable("entry"));
        assertTrue(res.isExecutable("then"));
        assertFalse(res.isExecutable("else"));
    }
}
package io.github.potjerodekool.nabu.compiler.backend.ir;

import io.github.potjerodekool.nabu.backend.ir.IRBasicBlock;
import io.github.potjerodekool.nabu.backend.ir.IRFunction;
import io.github.potjerodekool.nabu.backend.ir.Optimizer;
import io.github.potjerodekool.nabu.debug.SourceLocation;
import io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

class OptimizerTest {

    @Test
    void optimize() {
        final var function = new IRFunction("", null, List.of(), SourceLocation.UNKNOWN, 0);

        IRBasicBlock entryBlock = new IRBasicBlock("entry");
        entryBlock.add(new IRInstruction.Branch(
                "for.cond",
                SourceLocation.UNKNOWN
        ));
        function.addBlock(entryBlock);

        final var condBlock = new IRBasicBlock("for.cond");
        condBlock.add(new IRInstruction.Return(null, SourceLocation.UNKNOWN));
        function.addBlock(condBlock);

        final var optimizedFunction = Optimizer.optimize(function);
        System.out.println(optimizedFunction);
    }

    /** The handler block of a try/catch has no CFG predecessor; it must survive the optimizer. */
    @Test
    void handlerBlockOfTryCatchSurvives() {
        final var function = new IRFunction("f", null, List.of(), SourceLocation.UNKNOWN, 0);

        final var entry = new IRBasicBlock("entry");
        entry.add(new IRInstruction.Return(null, SourceLocation.UNKNOWN));
        function.addBlock(entry);

        final var handler = new IRBasicBlock("catch.handler");
        handler.add(new IRInstruction.TryCatchRegion("entry", "entry", "catch.handler",
                "java/lang/Exception", SourceLocation.UNKNOWN));
        handler.add(new IRInstruction.Return(null, SourceLocation.UNKNOWN));
        function.addBlock(handler);

        final var optimized = Optimizer.optimize(function);
        assertEquals(List.of("entry", "catch.handler"),
                optimized.blocks().stream().map(IRBasicBlock::label).toList());
    }
}

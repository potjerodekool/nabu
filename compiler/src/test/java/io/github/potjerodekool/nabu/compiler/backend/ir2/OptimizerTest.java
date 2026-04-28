package io.github.potjerodekool.nabu.compiler.backend.ir2;

import io.github.potjerodekool.nabu.debug.SourceLocation;
import io.github.potjerodekool.nabu.ir.IRBasicBlock;
import io.github.potjerodekool.nabu.ir.IRFunction;
import io.github.potjerodekool.nabu.ir.instructions.IRInstruction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

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
}
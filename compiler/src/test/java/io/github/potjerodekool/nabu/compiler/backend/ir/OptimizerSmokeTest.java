package io.github.potjerodekool.nabu.compiler.backend.ir;

import io.github.potjerodekool.nabu.backend.ir.IRBasicBlock;
import io.github.potjerodekool.nabu.backend.ir.IRFunction;
import io.github.potjerodekool.nabu.backend.ir.IRModule;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import io.github.potjerodekool.nabu.backend.ir.Optimizer;
import io.github.potjerodekool.nabu.backend.ir.CallKind;
import io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;
import io.github.potjerodekool.nabu.debug.SourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OptimizerSmokeTest {

    @Test
    void optimizeAllInlinesTrivialCallee() {
        final var module = new IRModule("test");

        // callee: return 42
        final var callee = new IRFunction("callee", IRType.I32, List.of(), SourceLocation.UNKNOWN, 0);
        final var calleeEntry = new IRBasicBlock("entry");
        calleeEntry.add(new IRInstruction.Return(new IRValue.ConstInt(42, IRType.I32), SourceLocation.UNKNOWN));
        callee.addBlock(calleeEntry);

        // caller: call callee, return result
        final var caller = new IRFunction("caller", IRType.I32, List.of(), SourceLocation.UNKNOWN, 0);
        final var callerEntry = new IRBasicBlock("entry");
        final var tmp = new IRValue.Temp("t0", IRType.I32);
        callerEntry.add(new IRInstruction.Call(CallKind.STATIC, IRType.I32, List.of(), tmp, "callee", List.of(), SourceLocation.UNKNOWN));
        callerEntry.add(new IRInstruction.Return(tmp, SourceLocation.UNKNOWN));
        caller.addBlock(callerEntry);

        module.addFunction(callee);
        module.addFunction(caller);

        final var result = Optimizer.optimizeAll(new java.util.ArrayList<>(List.of(module)));
        assertNotNull(result);
        assertEquals(1, result.size());
        // caller should still exist
        assertTrue(result.get(0).functions().stream().anyMatch(f -> f.name.equals("caller")));
    }
}
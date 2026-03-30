package io.github.potjerodekool.nabu.ir.instructions;

import io.github.potjerodekool.nabu.debug.SourceLocation;
import io.github.potjerodekool.nabu.ir.types.IRType;
import io.github.potjerodekool.nabu.ir.values.IRValue;

import java.util.List;

public sealed interface IRInstruction permits
        IRInstruction.BinaryOp,
        IRInstruction.Alloca,
        IRInstruction.Load,
        IRInstruction.Store,
        IRInstruction.Call,
        IRInstruction.IndirectCall,
        IRInstruction.Branch,
        IRInstruction.CondBranch,
        IRInstruction.Return,
        IRInstruction.Cast {

    /** Resultaat van de instructie; null als de instructie void is. */
    IRValue result();

    /** Bronlocatie voor debuginfo; UNKNOWN als niet beschikbaar. */
    SourceLocation location();

    // -------------------------------------------------------
    // Rekenkundige en logische operaties
    // -------------------------------------------------------

    record BinaryOp(
            IRValue        result,
            Op             op,
            IRValue        left,
            IRValue        right,
            SourceLocation location
    ) implements IRInstruction {

        public enum Op {
            ADD, SUB, MUL, DIV, MOD,
            AND, OR, XOR,
            EQ, NEQ, LT, LTE, GT, GTE
        }
    }

    // -------------------------------------------------------
    // Geheugen
    // -------------------------------------------------------

    record Alloca(
            IRValue        result,
            IRType allocType,
            SourceLocation location
    ) implements IRInstruction {}

    record Load(
            IRValue        result,
            IRValue        ptr,
            SourceLocation location
    ) implements IRInstruction {}

    record Store(
            IRValue        ptr,
            IRValue        value,
            SourceLocation location
    ) implements IRInstruction {
        public IRValue result() { return null; }
    }

    // -------------------------------------------------------
    // Aanroepen
    // -------------------------------------------------------

    record Call(
            IRValue        result,
            String         function,
            List<IRValue>  args,
            SourceLocation location
    ) implements IRInstruction {}

    record IndirectCall(
            IRValue           result,
            IRValue           callee,
            IRType.Function   fnType,
            List<IRValue>     args,
            SourceLocation    location
    ) implements IRInstruction {}

    // -------------------------------------------------------
    // Controle-stroom
    // -------------------------------------------------------

    record Branch(
            String         targetLabel,
            SourceLocation location
    ) implements IRInstruction {
        public IRValue result() { return null; }
    }

    record CondBranch(
            IRValue        condition,
            String         trueLabel,
            String         falseLabel,
            SourceLocation location
    ) implements IRInstruction {
        public IRValue result() { return null; }
    }

    record Return(
            IRValue        value,
            SourceLocation location
    ) implements IRInstruction {
        public IRValue result() { return null; }
    }

    // -------------------------------------------------------
    // Type-conversie
    // -------------------------------------------------------

    record Cast(
            IRValue        result,
            IRValue        source,
            IRType         targetType,
            SourceLocation location
    ) implements IRInstruction {}
}

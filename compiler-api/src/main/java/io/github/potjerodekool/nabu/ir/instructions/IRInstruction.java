package io.github.potjerodekool.nabu.ir.instructions;

import io.github.potjerodekool.nabu.debug.SourceLocation;
import io.github.potjerodekool.nabu.ir.CallKind;
import io.github.potjerodekool.nabu.ir.types.IRType;
import io.github.potjerodekool.nabu.ir.values.IRValue;

import java.util.List;
import java.util.Objects;

public sealed interface IRInstruction permits IRInstruction.Alloca, IRInstruction.AllocaArray, IRInstruction.BinaryOp, IRInstruction.Branch, IRInstruction.Call, IRInstruction.Cast, IRInstruction.CondBranch, IRInstruction.IndirectCall, IRInstruction.InstanceOf, IRInstruction.Load, IRInstruction.Pop, IRInstruction.Return, IRInstruction.Store, IRInstruction.Throw {

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

        public BinaryOp {
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(op, "op");
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(right, "right");
            Objects.requireNonNull(location, "location");
        }

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
            IRType type,
            IRValue        ptr,
            SourceLocation location
    ) implements IRInstruction {

    }

    record AllocaArray(IRValue result,
                       IRType allocType,
                       IRValue size,
                       SourceLocation location) implements IRInstruction {

    }

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
            CallKind callKind,
            IRType returnType,
            List<IRType> paramTypes,
            IRValue        result,
            String         function,
            List<IRValue>  args,
            SourceLocation location,
            io.github.potjerodekool.nabu.type.ExecutableType methodType) implements IRInstruction {}

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

    record InstanceOf(IRValue result,
                      IRValue source,
                      IRType type,
                      SourceLocation location) implements IRInstruction {
    }

    record Throw(IRValue result,
                 IRType type,
                 SourceLocation location) implements IRInstruction {

    }

    record Pop(IRValue result,
               SourceLocation location) implements IRInstruction {

    }
}

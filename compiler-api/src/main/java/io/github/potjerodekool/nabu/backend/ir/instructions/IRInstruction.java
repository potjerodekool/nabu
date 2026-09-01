package io.github.potjerodekool.nabu.backend.ir.instructions;

import io.github.potjerodekool.nabu.debug.SourceLocation;
import io.github.potjerodekool.nabu.backend.ir.CallKind;
import io.github.potjerodekool.nabu.backend.ir.IRBasicBlock;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;

import java.util.List;
import java.util.Objects;

public sealed interface IRInstruction permits IRInstruction.Alloca, IRInstruction.AllocaArray, IRInstruction.ArrayLength, IRInstruction.ArrayLoad, IRInstruction.ArrayStore, IRInstruction.BinaryOp, IRInstruction.Branch, IRInstruction.Call, IRInstruction.Cast, IRInstruction.CondBranch, IRInstruction.HeapAlloc, IRInstruction.IndirectCall, IRInstruction.InstanceOf, IRInstruction.Load, IRInstruction.MonitorEnter, IRInstruction.MonitorExit, IRInstruction.Move, IRInstruction.Phi, IRInstruction.Pop, IRInstruction.Return, IRInstruction.Store, IRInstruction.Throw, IRInstruction.TryCatchRegion {

    /** Resultaat van de instructie; null als de instructie void is. */
    IRValue result();

    /** Bronlocatie voor debuginfo; UNKNOWN als niet beschikbaar. */
    SourceLocation location();

    // -------------------------------------------------------
    // Rekenkundige en logische operaties
    // -------------------------------------------------------

    record BinaryOp(
            IRValue result,
            Op             op,
            IRValue left,
            IRValue right,
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
            BITAND, BITOR, BITXOR,
            EQ, NEQ, LT, LTE, GT, GTE
        }
    }

    // -------------------------------------------------------
    // Geheugen
    // -------------------------------------------------------

    record Alloca(
            IRValue result,
            IRType allocType,
            SourceLocation location
    ) implements IRInstruction {}

    record Load(
            IRValue result,
            IRType type,
            IRValue ptr,
            SourceLocation location
    ) implements IRInstruction {

    }

    record AllocaArray(IRValue result,
                       IRType allocType,
                       IRValue size,
                       SourceLocation location) implements IRInstruction {

    }

    record ArrayLoad(
            IRValue result,
            IRValue array,
            IRValue index,
            IRType elemType,
            SourceLocation location
    ) implements IRInstruction {}

    record ArrayStore(
            IRValue array,
            IRValue index,
            IRValue value,
            IRType elemType,
            SourceLocation location
    ) implements IRInstruction {
        public IRValue result() { return null; }
    }

    record ArrayLength(
            IRValue result,
            IRValue array,
            SourceLocation location
    ) implements IRInstruction {}

    record Store(
            IRValue ptr,
            IRValue value,
            SourceLocation location
    ) implements IRInstruction {
        public IRValue result() { return null; }
    }

    record HeapAlloc(
            IRValue result,
            IRType allocType,
            SourceLocation location
    ) implements IRInstruction {}

    // -------------------------------------------------------
    // Aanroepen
    // -------------------------------------------------------

    record Call(
            CallKind callKind,
            IRType returnType,
            List<IRType> paramTypes,
            IRValue result,
            String         function,
            List<IRValue>  args,
            SourceLocation location) implements IRInstruction {}

    record IndirectCall(
            IRValue result,
            IRValue callee,
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
            IRValue condition,
            String         trueLabel,
            String         falseLabel,
            SourceLocation location
    ) implements IRInstruction {
        public IRValue result() { return null; }
    }

    record Return(
            IRValue value,
            SourceLocation location
    ) implements IRInstruction {
        public IRValue result() { return null; }
    }

    // -------------------------------------------------------
    // Type-conversie
    // -------------------------------------------------------

    record Cast(
            IRValue result,
            IRValue source,
            IRType targetType,
            SourceLocation location
    ) implements IRInstruction {}

    record InstanceOf(IRValue result,
                      IRValue source,
                      IRType type,
                      SourceLocation location) implements IRInstruction {
    }

    record MonitorEnter(IRValue object,
                        SourceLocation location) implements IRInstruction {
        public IRValue result() { return null; }
    }

    record MonitorExit(IRValue object,
                       SourceLocation location) implements IRInstruction {
        public IRValue result() { return null; }
    }

    record Throw(IRValue result,
                 IRType type,
                 SourceLocation location) implements IRInstruction {

    }

    record Pop(IRValue result,
               SourceLocation location) implements IRInstruction {

    }

    // -------------------------------------------------------
    // SSA Move (voor SSA renaming)
    // -------------------------------------------------------

    /**
     * SSA move-instructie: kopieert een waarde naar een nieuw SSA-register.
     * Wordt gebruikt bij SSA-construction om loads te vervangen door
     * directe SSA-waarde-referenties.
     *
     * @param result het nieuwe SSA-register
     * @param value  de bronwaarde
     */
    record Move(
            IRValue result,
            IRValue value,
            SourceLocation location
    ) implements IRInstruction {}

    // -------------------------------------------------------
    // SSA Phi-functie
    // -------------------------------------------------------

    /**
     * SSA phi-instructie: kiest de waarde op basis van het voorafgaande blok.
     * Moet het eerste instrument zijn in een basisblok.
     *
     * @param result         het SSA-register dat de samengevoegde waarde ontvangt
     * @param incomingValues lijst van (waarde, bronblok) paren — een per predecessor
     */
    record Phi(
            IRValue result,
            List<Incoming> incomingValues,
            SourceLocation location
    ) implements IRInstruction {

        public record Incoming(IRValue value, IRBasicBlock fromBlock) {
            public Incoming {
                Objects.requireNonNull(value, "value");
                Objects.requireNonNull(fromBlock, "fromBlock");
            }
        }

        public Phi {
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(incomingValues, "incomingValues");
            Objects.requireNonNull(location, "location");
        }
    }

    // -------------------------------------------------------
    // Exception handling
    // -------------------------------------------------------

    /**
     * Markeert een catch-blok met informatie over het bijhorende try-bereik.
     * Wordt aan het begin van elk catch-blok geplaatst.
     *
     * @param tryStartLabel label van het eerste blok van de try-body
     * @param tryEndLabel   label van het laatste blok van de try-body (exclusief)
     * @param handlerLabel  label van dit catch-blok
     * @param exceptionType interne naam van het exception-type (null = vang alles)
     */
    record TryCatchRegion(
            String tryStartLabel,
            String tryEndLabel,
            String handlerLabel,
            String exceptionType,
            SourceLocation location
    ) implements IRInstruction {
        public IRValue result() { return null; }
    }
}

package io.github.potjerodekool.nabu.compiler.backend.jvm;

import io.github.potjerodekool.nabu.backend.jvm.Linearizer;
import io.github.potjerodekool.nabu.debug.SourceLocation;
import io.github.potjerodekool.nabu.backend.ir.IRBasicBlock;
import io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LinearizerTest {

    private static final SourceLocation NO_LOC = SourceLocation.UNKNOWN;

    @Test
    void linearizeSingleBlockReturnsSameContent() {
        final var temp = new IRValue.Temp("%cond", IRType.BOOL);
        final var block = new IRBasicBlock("entry", List.of(
                new IRInstruction.Branch("exit", NO_LOC)
        ));

        final var result = Linearizer.linearize(List.of(block));

        assertEquals(1, result.size());
        assertEquals("entry", result.get(0).label());
        assertEquals(1, result.get(0).instructions().size());
    }

    @Test
    void linearizeInvertsCondBranchWhenBothTargetsUnvisited() {
        final var lhs = new IRValue.Temp("%lhs", IRType.I32);
        final var rhs = new IRValue.Temp("%rhs", IRType.I32);
        final var cmpResult = new IRValue.Temp("%cmp", IRType.BOOL);

        final var entryBlock = new IRBasicBlock("entry", List.of(
                new IRInstruction.BinaryOp(cmpResult, IRInstruction.BinaryOp.Op.LT, lhs, rhs, NO_LOC),
                new IRInstruction.CondBranch(cmpResult, "then", "else", NO_LOC)
        ));
        final var thenBlock = new IRBasicBlock("then", List.of(
                new IRInstruction.Branch("exit", NO_LOC)
        ));
        final var elseBlock = new IRBasicBlock("else", List.of(
                new IRInstruction.Branch("exit", NO_LOC)
        ));
        final var exitBlock = new IRBasicBlock("exit", List.of(
                new IRInstruction.Return(IRValue.ofI32(0), NO_LOC)
        ));

        final var result = Linearizer.linearize(List.of(entryBlock, thenBlock, elseBlock, exitBlock));

        assertEquals(4, result.size());

        final var entry = result.get(0);
        assertEquals("entry", entry.label());
        assertEquals(2, entry.instructions().size());

        final var binop = entry.instructions().get(0);
        assertInstanceOf(IRInstruction.BinaryOp.class, binop);
        final var invertedBinop = (IRInstruction.BinaryOp) binop;
        assertEquals(IRInstruction.BinaryOp.Op.GTE, invertedBinop.op());

        final var condBranch = entry.instructions().get(1);
        assertInstanceOf(IRInstruction.CondBranch.class, condBranch);
        final var invertedCond = (IRInstruction.CondBranch) condBranch;
        assertEquals("else", invertedCond.trueLabel());
        assertEquals("then", invertedCond.falseLabel());
    }

    @Test
    void linearizeKeepsOriginalCondBranchWhenTargetAlreadyVisited() {
        final var lhs = new IRValue.Temp("%lhs", IRType.I32);
        final var rhs = new IRValue.Temp("%rhs", IRType.I32);
        final var cmpResult = new IRValue.Temp("%cmp", IRType.BOOL);

        final var thenBlock = new IRBasicBlock("then", List.of(
                new IRInstruction.Branch("entry", NO_LOC)
        ));
        final var entryBlock = new IRBasicBlock("entry", List.of(
                new IRInstruction.BinaryOp(cmpResult, IRInstruction.BinaryOp.Op.LT, lhs, rhs, NO_LOC),
                new IRInstruction.CondBranch(cmpResult, "then", "else", NO_LOC)
        ));
        final var elseBlock = new IRBasicBlock("else", List.of(
                new IRInstruction.Return(IRValue.ofI32(0), NO_LOC)
        ));

        final var result = Linearizer.linearize(List.of(thenBlock, entryBlock, elseBlock));

        final var entry = result.get(1);
        final var condBranch = entry.instructions().get(1);
        assertInstanceOf(IRInstruction.CondBranch.class, condBranch);
        final var cond = (IRInstruction.CondBranch) condBranch;
        assertEquals("then", cond.trueLabel());
        assertEquals("else", cond.falseLabel());
    }

    @Test
    void linearizeWithoutBinaryOpBeforeCondBranchKeepsOriginal() {
        final var temp = new IRValue.Temp("%cond", IRType.BOOL);

        final var entryBlock = new IRBasicBlock("entry", List.of(
                new IRInstruction.CondBranch(temp, "then", "else", NO_LOC)
        ));
        final var thenBlock = new IRBasicBlock("then", List.of(
                new IRInstruction.Branch("exit", NO_LOC)
        ));
        final var elseBlock = new IRBasicBlock("else", List.of(
                new IRInstruction.Branch("exit", NO_LOC)
        ));
        final var exitBlock = new IRBasicBlock("exit", List.of(
                new IRInstruction.Return(IRValue.ofI32(0), NO_LOC)
        ));

        final var result = Linearizer.linearize(List.of(entryBlock, thenBlock, elseBlock, exitBlock));

        final var entry = result.get(0);
        final var condBranch = entry.instructions().get(0);
        assertInstanceOf(IRInstruction.CondBranch.class, condBranch);
        final var cond = (IRInstruction.CondBranch) condBranch;
        assertEquals("then", cond.trueLabel());
        assertEquals("else", cond.falseLabel());
    }

    @Test
    void linearizeEqBecomesNeq() {
        assertInversion(IRInstruction.BinaryOp.Op.EQ, IRInstruction.BinaryOp.Op.NEQ);
    }

    @Test
    void linearizeNeqBecomesEq() {
        assertInversion(IRInstruction.BinaryOp.Op.NEQ, IRInstruction.BinaryOp.Op.EQ);
    }

    @Test
    void linearizeLtBecomesGte() {
        assertInversion(IRInstruction.BinaryOp.Op.LT, IRInstruction.BinaryOp.Op.GTE);
    }

    @Test
    void linearizeLteBecomesGt() {
        assertInversion(IRInstruction.BinaryOp.Op.LTE, IRInstruction.BinaryOp.Op.GT);
    }

    @Test
    void linearizeGtBecomesLte() {
        assertInversion(IRInstruction.BinaryOp.Op.GT, IRInstruction.BinaryOp.Op.LTE);
    }

    @Test
    void linearizeGteBecomesLt() {
        assertInversion(IRInstruction.BinaryOp.Op.GTE, IRInstruction.BinaryOp.Op.LT);
    }

    @Test
    void linearizeAndBecomesOr() {
        assertInversion(IRInstruction.BinaryOp.Op.AND, IRInstruction.BinaryOp.Op.OR);
    }

    @Test
    void linearizeOrBecomesAnd() {
        assertInversion(IRInstruction.BinaryOp.Op.OR, IRInstruction.BinaryOp.Op.AND);
    }

    @Test
    void linearizeMultipleBlocksInSequence() {
        final var block1 = new IRBasicBlock("b1", List.of(
                new IRInstruction.Branch("b2", NO_LOC)
        ));
        final var block2 = new IRBasicBlock("b2", List.of(
                new IRInstruction.Return(IRValue.ofI32(42), NO_LOC)
        ));

        final var result = Linearizer.linearize(List.of(block1, block2));

        assertEquals(2, result.size());
        assertEquals("b1", result.get(0).label());
        assertEquals("b2", result.get(1).label());
    }

    @Test
    void linearizeEmptyListReturnsEmptyList() {
        final var result = Linearizer.linearize(List.of());
        assertTrue(result.isEmpty());
    }

    @Test
    void linearizePreservesNonCondBranchInstructions() {
        final var result1 = new IRValue.Temp("%r1", IRType.I32);
        final var result2 = new IRValue.Temp("%r2", IRType.I32);

        final var block = new IRBasicBlock("entry", List.of(
                new IRInstruction.Move(result1, IRValue.ofI32(10), NO_LOC),
                new IRInstruction.Move(result2, IRValue.ofI32(20), NO_LOC),
                new IRInstruction.Return(result2, NO_LOC)
        ));

        final var result = Linearizer.linearize(List.of(block));

        assertEquals(1, result.size());
        assertEquals(3, result.get(0).instructions().size());
        assertInstanceOf(IRInstruction.Move.class, result.get(0).instructions().get(0));
        assertInstanceOf(IRInstruction.Move.class, result.get(0).instructions().get(1));
        assertInstanceOf(IRInstruction.Return.class, result.get(0).instructions().get(2));
    }

    private void assertInversion(IRInstruction.BinaryOp.Op original, IRInstruction.BinaryOp.Op expected) {
        final var lhs = new IRValue.Temp("%lhs", IRType.I32);
        final var rhs = new IRValue.Temp("%rhs", IRType.I32);
        final var cmpResult = new IRValue.Temp("%cmp", IRType.BOOL);

        final var entryBlock = new IRBasicBlock("entry", List.of(
                new IRInstruction.BinaryOp(cmpResult, original, lhs, rhs, NO_LOC),
                new IRInstruction.CondBranch(cmpResult, "then", "else", NO_LOC)
        ));
        final var thenBlock = new IRBasicBlock("then", List.of(
                new IRInstruction.Branch("exit", NO_LOC)
        ));
        final var elseBlock = new IRBasicBlock("else", List.of(
                new IRInstruction.Branch("exit", NO_LOC)
        ));
        final var exitBlock = new IRBasicBlock("exit", List.of(
                new IRInstruction.Return(IRValue.ofI32(0), NO_LOC)
        ));

        final var result = Linearizer.linearize(List.of(entryBlock, thenBlock, elseBlock, exitBlock));

        final var entry = result.get(0);
        final var binop = (IRInstruction.BinaryOp) entry.instructions().get(0);
        assertEquals(expected, binop.op());
    }
}

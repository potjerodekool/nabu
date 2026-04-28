package io.github.potjerodekool.nabu.compiler.backend.asm;

import io.github.potjerodekool.nabu.ir.IRBasicBlock;
import io.github.potjerodekool.nabu.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.ir.values.IRValue;
import io.github.potjerodekool.nabu.tools.TodoException;

import java.util.*;

public final class Linearizer {

    private Linearizer() {
    }

    public static List<IRBasicBlock> linearize(final List<IRBasicBlock> blocks) {
        final var visited = new HashSet<String>();

        return blocks.stream()
                .map(block -> linearize(block, visited))
                .toList();
    }

    private static IRBasicBlock linearize(final IRBasicBlock block, final HashSet<String> visited) {
        final var newInstructions = new ArrayList<IRInstruction>();

        for (final var instruction : block.instructions()) {
            if (instruction instanceof IRInstruction.CondBranch(
                    IRValue condition, String trueLabel, String falseLabel,
                    io.github.potjerodekool.nabu.debug.SourceLocation location
            )) {
                if (!visited.contains(trueLabel) && !visited.contains(falseLabel)) {
                    //invert condition
                    final var previousInstruction = newInstructions.getLast();

                    if (previousInstruction instanceof IRInstruction.BinaryOp binaryOp) {
                        final var newBinop = invert(binaryOp);
                        newInstructions.removeLast();
                        newInstructions.add(newBinop);
                    }

                    final var invertedCondition = invert(condition);
                    final var newCondBranch = new IRInstruction.CondBranch(
                            invertedCondition,
                            falseLabel,
                            trueLabel,
                            location
                    );
                    newInstructions.add(newCondBranch);
                } else {
                    newInstructions.add(instruction);
                }
            } else {
                newInstructions.add(instruction);
            }
        }

        visited.add(block.label());

        return new IRBasicBlock(block.label(), newInstructions);
    }

    private static IRValue invert(final IRValue value) {
        if (value instanceof IRValue.Temp) {
            return value;
        }

        throw new TodoException();
    }

    private static IRInstruction.BinaryOp invert(final IRInstruction.BinaryOp binaryOp) {
        final var newOp = switch (binaryOp.op()) {
            case LT -> IRInstruction.BinaryOp.Op.GTE;
            case LTE -> IRInstruction.BinaryOp.Op.GT;
            case EQ -> IRInstruction.BinaryOp.Op.NEQ;
            case GTE -> IRInstruction.BinaryOp.Op.LT;
            case GT -> IRInstruction.BinaryOp.Op.LTE;
            case NEQ -> IRInstruction.BinaryOp.Op.EQ;
            default -> throw new TodoException();
        };

        return new IRInstruction.BinaryOp(binaryOp.result(), newOp, binaryOp.left(), binaryOp.right(), binaryOp.location());
    }
}

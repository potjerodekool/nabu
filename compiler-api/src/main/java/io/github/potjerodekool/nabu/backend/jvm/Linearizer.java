package io.github.potjerodekool.nabu.backend.jvm;

import io.github.potjerodekool.nabu.debug.SourceLocation;
import io.github.potjerodekool.nabu.backend.ir.IRBasicBlock;
import io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;

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
                    SourceLocation location
            )) {
            if (!visited.contains(trueLabel) && !visited.contains(falseLabel)) {
                //Invert condition zodat de false-tak kan "doorvallen".
                //Alleen correct als de vergelijkingsinstructie direct
                //ervoor staat; anders worden de labels gewisseld zonder
                //dat de conditie is geïnverteerd (inconsistent IR).
                final var previousInstruction = newInstructions.isEmpty()
                        ? null
                        : newInstructions.getLast();

                if (previousInstruction instanceof IRInstruction.BinaryOp binaryOp) {
                    final var newBinop = invert(binaryOp);
                    newInstructions.removeLast();
                    newInstructions.add(newBinop);

                    final var invertedCondition = invert(condition);
                    final var newCondBranch = new IRInstruction.CondBranch(
                            invertedCondition,
                            falseLabel,
                            trueLabel,
                            location
                    );
                    newInstructions.add(newCondBranch);
                } else {
                    // Conditie kan niet consistent geïnverteerd worden:
                    // behoud de oorspronkelijke true/false-volgorde.
                    newInstructions.add(instruction);
                }
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
        if (value instanceof IRValue.ConstBool(boolean value1)) {
            return IRValue.ofBool(!value1);
        }
        if (value instanceof IRValue.Named) {
            return value;
        }
        throw new UnsupportedOperationException("Cannot invert IRValue: " + value.getClass().getSimpleName());
    }

    private static IRInstruction.BinaryOp invert(final IRInstruction.BinaryOp binaryOp) {
        final var newOp = switch (binaryOp.op()) {
            case LT -> IRInstruction.BinaryOp.Op.GTE;
            case LTE -> IRInstruction.BinaryOp.Op.GT;
            case EQ -> IRInstruction.BinaryOp.Op.NEQ;
            case GTE -> IRInstruction.BinaryOp.Op.LT;
            case GT -> IRInstruction.BinaryOp.Op.LTE;
            case NEQ -> IRInstruction.BinaryOp.Op.EQ;
            case AND -> IRInstruction.BinaryOp.Op.OR;
            case OR -> IRInstruction.BinaryOp.Op.AND;
            case XOR -> IRInstruction.BinaryOp.Op.XOR;
            default -> throw new UnsupportedOperationException("Cannot invert op: " + binaryOp.op());
        };

        return new IRInstruction.BinaryOp(binaryOp.result(), newOp, binaryOp.left(), binaryOp.right(), binaryOp.location());
    }
}

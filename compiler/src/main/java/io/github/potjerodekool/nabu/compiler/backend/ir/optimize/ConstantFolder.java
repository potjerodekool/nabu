package io.github.potjerodekool.nabu.compiler.backend.ir.optimize;

import io.github.potjerodekool.nabu.compiler.ir.IRBasicBlock;
import io.github.potjerodekool.nabu.compiler.ir.IRFunction;
import io.github.potjerodekool.nabu.compiler.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.compiler.ir.values.IRValue;

import java.util.ArrayList;

/**
 * Constant Folding: evalueert rekenkundige en logische operaties
 * op constante waarden tijdens de compilatie.
 *
 * Voorbeelden:
 *   %1 = 3 + 4  →  %1 = 7
 *   %2 = true && false → %2 = false
 *   %3 = 10 > 5 → %3 = true
 */
public class ConstantFolder implements OptimizationPass {

    @Override
    public boolean run(final IRFunction function) {
        boolean changed = false;

        for (final var block : function.blocks()) {
            changed |= foldBlock(block);
        }

        return changed;
    }

    private boolean foldBlock(final IRBasicBlock block) {
        boolean changed = false;
        final var instructions = new ArrayList<>(block.instructions());

        for (int i = 0; i < instructions.size(); i++) {
            final var instr = instructions.get(i);

            if (instr instanceof IRInstruction.BinaryOp op) {
                final var folded = foldBinaryOp(op);
                if (folded != null) {
                    block.setInstruction(i, folded);
                    changed = true;
                }
            }
        }

        return changed;
    }

    private IRInstruction foldBinaryOp(final IRInstruction.BinaryOp op) {
        if (op.left() instanceof IRValue.ConstInt leftConst &&
            op.right() instanceof IRValue.ConstInt rightConst) {
            return foldIntOp(op, leftConst, rightConst);
        }

        if (op.left() instanceof IRValue.ConstBool leftBool &&
            op.right() instanceof IRValue.ConstBool rightBool) {
            return foldBoolOp(op, leftBool, rightBool);
        }

        return null;
    }

    private IRInstruction foldIntOp(final IRInstruction.BinaryOp op,
                                    final IRValue.ConstInt left,
                                    final IRValue.ConstInt right) {
        final long l = left.value();
        final long r = right.value();

        final boolean isComparison = switch (op.op()) {
            case EQ, NEQ, LT, LTE, GT, GTE -> true;
            default -> false;
        };

        if (isComparison) {
            final boolean result = switch (op.op()) {
                case EQ  -> l == r;
                case NEQ -> l != r;
                case LT  -> l < r;
                case LTE -> l <= r;
                case GT  -> l > r;
                case GTE -> l >= r;
                default -> false;
            };
            return new IRInstruction.Move(
                    op.result(),
                    new IRValue.ConstBool(result),
                    op.location()
            );
        }

        final var result = switch (op.op()) {
            case ADD -> l + r;
            case SUB -> l - r;
            case MUL -> l * r;
            case DIV -> r == 0 ? l : l / r;
            case MOD -> r == 0 ? l : l % r;
            case AND -> l & r;
            case OR  -> l | r;
            case XOR -> l ^ r;
            case BITAND -> l & r;
            case BITOR  -> l | r;
            case BITXOR -> l ^ r;
            default -> l;
        };

        return new IRInstruction.Move(
                op.result(),
                new IRValue.ConstInt(result, left.type()),
                op.location()
        );
    }

    private IRInstruction foldBoolOp(final IRInstruction.BinaryOp op,
                                     final IRValue.ConstBool left,
                                     final IRValue.ConstBool right) {
        final boolean l = left.value();
        final boolean r = right.value();

        final var result = switch (op.op()) {
            case AND -> l && r;
            case OR  -> l || r;
            case EQ  -> l == r;
            case NEQ -> l != r;
            default -> {
                yield l; // niet-foldsbaar, geef linker operand terug
            }
        };

        if ((op.op() == IRInstruction.BinaryOp.Op.AND ||
             op.op() == IRInstruction.BinaryOp.Op.OR  ||
             op.op() == IRInstruction.BinaryOp.Op.EQ  ||
             op.op() == IRInstruction.BinaryOp.Op.NEQ)) {
            return new IRInstruction.Move(
                    op.result(),
                    new IRValue.ConstBool(result),
                    op.location()
            );
        }

        return null;
    }
}

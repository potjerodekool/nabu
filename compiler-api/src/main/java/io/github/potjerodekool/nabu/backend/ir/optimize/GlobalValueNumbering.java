package io.github.potjerodekool.nabu.backend.ir.optimize;

import io.github.potjerodekool.nabu.backend.ir.IRBasicBlock;
import io.github.potjerodekool.nabu.backend.ir.IRFunction;
import io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;

import java.util.*;

/**
 * Global Value Numbering (GVN): herkent equivalente expressies
 * en vervangt duplicaten door hun eerdere resultaat.
 *
 * Algoritme:
 *   1. Wijs een "value number" toe aan elke constante
 *   2. Voor elke BinaryOp: bereken een hash van (opcode, vn(left), vn(right))
 *   3. Als dezelfde hash al bestaat → vervang door het eerdere resultaat
 *
 * Voorbeeld:
 *   %a = 3 + 4    → vn(3)=1, vn(4)=2, hash(ADD,1,2) = #1
 *   %b = 3 + 4    → hash(ADD,1,2) = #1 → %b = %a
 */
public class GlobalValueNumbering implements OptimizationPass {

    @Override
    public boolean run(final IRFunction function) {
        boolean changed = false;

        for (final var block : function.blocks()) {
            changed |= gvnBlock(block);
        }

        return changed;
    }

    /** Sleutel van een expressie: opcode plus de value-keys van beide operanden. */
    private record ExprKey(IRInstruction.BinaryOp.Op op, Object left, Object right) {
    }

    private boolean gvnBlock(final IRBasicBlock block) {
        boolean changed = false;

        // Value numbers: temp name → value number. Een temp die in dit blok
        // niet gedefinieerd wordt (parameter, waarde uit een ander blok) krijgt
        // bij eerste gebruik een eigen nummer: twee verschillende parameters
        // mogen nooit hetzelfde nummer delen.
        final Map<String, Long> valueNumbers = new HashMap<>();
        final long[] nextVN = {1};

        // Exacte sleutel → het eerder geziene resultaat. Geen int-hash als
        // sleutel: een botsing zou twee verschillende expressies samenvoegen.
        final Map<ExprKey, IRValue> seenExpressions = new HashMap<>();

        for (final var instr : new ArrayList<>(block.instructions())) {
            if (instr instanceof IRInstruction.Phi) {
                // Phi's hebben speciale behandeling nodig; sla over
                final var name = IRValue.nameOf(instr.result());
                valueNumbers.put(name, nextVN[0]++);
                continue;
            }

            if (instr instanceof IRInstruction.BinaryOp op) {
                final var leftKey = valueKey(op.left(), valueNumbers, nextVN);
                final var rightKey = valueKey(op.right(), valueNumbers, nextVN);
                final var existing = leftKey != null && rightKey != null
                        ? seenExpressions.get(new ExprKey(op.op(), leftKey, rightKey))
                        : null;

                if (existing != null && existing != op.result()) {
                    // Vervang door het eerdere resultaat
                    block.setInstruction(block.instructions().indexOf(instr),
                            new IRInstruction.Move(op.result(), existing, op.location()));
                    changed = true;
                } else {
                    valueNumbers.put(IRValue.nameOf(op.result()), nextVN[0]++);
                    if (leftKey != null && rightKey != null) {
                        seenExpressions.put(new ExprKey(op.op(), leftKey, rightKey), op.result());
                    }
                }
            } else if (instr.result() != null &&
                       instr.result() instanceof IRValue.Temp temp) {
                // Andere instructies met resultaat: ken een nieuw VN toe
                valueNumbers.put(temp.name(), nextVN[0]++);
            }
        }

        return changed;
    }

    /**
     * De identiteit van een operand voor GVN, of null als die niet betrouwbaar
     * te bepalen is (dan wordt de expressie niet samengevoegd).
     */
    private Object valueKey(final IRValue value,
                            final Map<String, Long> valueNumbers,
                            final long[] nextVN) {
        if (value instanceof IRValue.ConstInt c) {
            return "i" + c.type() + ":" + c.value();
        }

        if (value instanceof IRValue.ConstBool b) {
            return b.value() ? "true" : "false";
        }

        if (value instanceof IRValue.Temp temp) {
            return valueNumbers.computeIfAbsent(temp.name(), n -> nextVN[0]++);
        }

        return null;
    }
}

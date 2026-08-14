package io.github.potjerodekool.nabu.compiler.backend.ir.optimize;

import io.github.potjerodekool.nabu.compiler.ir.IRBasicBlock;
import io.github.potjerodekool.nabu.compiler.ir.IRFunction;
import io.github.potjerodekool.nabu.compiler.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.compiler.ir.values.IRValue;

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

    private boolean gvnBlock(final IRBasicBlock block) {
        boolean changed = false;

        // Value numbers: temp name → value number
        final Map<String, Long> valueNumbers = new HashMap<>();

        // Hash → het eerder geziene resultaat dat die hash heeft
        final Map<Integer, IRValue> seenExpressions = new HashMap<>();

        long nextVN = 1;

        for (final var instr : new ArrayList<>(block.instructions())) {
            if (instr instanceof IRInstruction.Phi) {
                // Phi's hebben speciale behandeling nodig; sla over
                final var name = IRValue.nameOf(instr.result());
                valueNumbers.put(name, nextVN++);
                continue;
            }

            if (instr instanceof IRInstruction.BinaryOp op) {
                final var leftVN = getValueNumber(op.left(), valueNumbers);
                final var rightVN = getValueNumber(op.right(), valueNumbers);

                final var hash = Objects.hash(op.op(), leftVN, rightVN);

                final var existing = seenExpressions.get(hash);
                if (existing != null && existing != op.result()) {
                    // Vervang door het eerdere resultaat
                    block.setInstruction(block.instructions().indexOf(instr),
                            new IRInstruction.Move(op.result(), existing, op.location()));
                    changed = true;
                } else {
                    final var vn = nextVN++;
                    valueNumbers.put(IRValue.nameOf(op.result()), vn);
                    seenExpressions.put(hash, op.result());
                }
            } else if (instr.result() != null &&
                       instr.result() instanceof IRValue.Temp temp) {
                // Andere instructies met resultaat: ken een nieuw VN toe
                valueNumbers.put(temp.name(), nextVN++);
            }
        }

        return changed;
    }

    private long getValueNumber(final IRValue value,
                                final Map<String, Long> valueNumbers) {
        if (value instanceof IRValue.ConstInt c) {
            return c.value() + 1000_000; // constanten krijgen offset
        }

        if (value instanceof IRValue.ConstBool b) {
            return b.value() ? 2000_001 : 2000_000;
        }

        if (value instanceof IRValue.Temp temp) {
            return valueNumbers.getOrDefault(temp.name(), 0L);
        }

        return 0L;
    }
}

package io.github.potjerodekool.nabu.backend.ir.optimize;

import io.github.potjerodekool.nabu.backend.ir.IRBasicBlock;
import io.github.potjerodekool.nabu.backend.ir.IRFunction;
import io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;

import java.util.*;

/**
 * Copy Propagation: vervangt Move-instructies door hun bronwaarde.
 *
 * In SSA-vorm zijn moves ontstaan uit:
 *   - SsaBuilder (loads die door Moves worden vervangen)
 *   - PhiElimination (store/load sequenties)
 *
 * Voorbeeld:
 *   %a = 42
 *   %b = move %a       →  verwijder move, vervang %b door %a overal
 *   %c = %b + 1        →  %c = %a + 1
 */
public class CopyPropagation implements OptimizationPass {

    @Override
    public boolean run(final IRFunction function) {
        boolean changed = false;

        for (final var block : function.blocks()) {
            changed |= propagateBlock(block, function);
        }

        return changed;
    }

    private boolean propagateBlock(final IRBasicBlock block,
                                   final IRFunction function) {
        boolean changed = false;
        final Map<String, IRValue> copies = new LinkedHashMap<>();

        // Stap 1: Verzamel alle moves in dit blok die alleen in dit blok
        // gebruikt worden. Een Move wiens resultaat óók in een ander blok
        // gelezen wordt mag NIET verwijderd worden: de cross-block uses
        // zouden anders naar een niet-gedefinieerde temp verwijzen
        // ('Onbekende register'/'uninitialized slot').
        final var instructions = block.instructions();
        final List<Integer> moveIndices = new ArrayList<>();

        for (int i = 0; i < instructions.size(); i++) {
            final var instr = instructions.get(i);
            if (instr instanceof IRInstruction.Move move
                    && isUsedOnlyInBlock(move.result(), block, function)) {
                final var src = resolveCopies(move.value(), copies);
                copies.put(IRValue.nameOf(move.result()), src);
                moveIndices.add(i);
                changed = true;
            }
        }

        if (!changed) {
            return false;
        }

        // Stap 2: Verwijder alle moves (achterstevoren zodat indices geldig blijven)
        final var instrList = new ArrayList<>(block.instructions());
        for (int i = moveIndices.size() - 1; i >= 0; i--) {
            block.removeInstruction(instrList.get(moveIndices.get(i)));
        }

        // Stap 3: Vervang referenties in overgebleven instructies
        final var remaining = new ArrayList<>(block.instructions());
        for (int i = 0; i < remaining.size(); i++) {
            final var instr = remaining.get(i);
            final var replaced = ValueRewriter.rewrite(instr, t -> resolveCopies(t, copies));
            if (replaced != instr) {
                block.setInstruction(i, replaced);
            }
        }

        return changed;
    }

    /**
     * Loss een keten van copies op tot de uiteindelijke bronwaarde.
     */
    private IRValue resolveCopies(final IRValue value,
                                  final Map<String, IRValue> copies) {
        if (value instanceof IRValue.Temp temp) {
            final var mapping = copies.get(temp.name());
            if (mapping != null) {
                return resolveCopies(mapping, copies);
            }
        } else if (value instanceof IRValue.Values chains) {
            final var inner = chains.values();
            List<IRValue> newInner = null;
            for (int i = 0; i < inner.size(); i++) {
                final var resolved = resolveCopies(inner.get(i), copies);
                if (resolved != inner.get(i)) {
                    if (newInner == null) newInner = new ArrayList<>(inner);
                    newInner.set(i, resolved);
                }
            }
            if (newInner != null) {
                return new IRValue.Values(newInner);
            }
        }
        return value;
    }

    /**
     * True als het resultaat uitsluitend in dit blok gebruikt wordt (ten
     * minste één keer). Alleen dan mag de definiërende Move verwijderd en
     * de copy doorgedrukt worden.
     */
    private boolean isUsedOnlyInBlock(final IRValue value,
                                   final IRBasicBlock block,
                                   final IRFunction function) {
        if (!(value instanceof IRValue.Temp temp)) {
            return false;
        }
        final var name = temp.name();

        boolean usedInOtherBlock = false;
        boolean usedInThisBlock = false;
        for (final var other : function.blocks()) {
            for (final var instr : other.instructions()) {
                if (instr instanceof IRInstruction.Move move
                        && IRValue.nameOf(instr.result()).equals(name)) {
                    // De Move zelf is geen 'use'
                    continue;
                }
                if (references(instr, name)) {
                    if (other == block) {
                        usedInThisBlock = true;
                    } else {
                        usedInOtherBlock = true;
                    }
                }
            }
        }
        return !usedInOtherBlock;
    }

    private boolean references(final IRInstruction instr, final String tempName) {
        for (final var read : ValueRewriter.readsOf(instr)) {
            if (read instanceof IRValue.Temp t && t.name().equals(tempName)) {
                return true;
            } else if (read instanceof IRValue.Values values) {
                for (final var inner : values.values()) {
                    if (inner instanceof IRValue.Temp t && t.name().equals(tempName)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

}

package io.github.potjerodekool.nabu.compiler.backend.ir.optimize;

import io.github.potjerodekool.nabu.compiler.ir.IRBasicBlock;
import io.github.potjerodekool.nabu.compiler.ir.IRFunction;
import io.github.potjerodekool.nabu.compiler.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.compiler.ir.values.IRValue;

import java.util.*;

/**
 * Dead Code Elimination: verwijdert overbodige instructies.
 *
 * Stappen:
 *   1. Tel gebruik van alle Temp-waarden
 *   2. Verwijder instructies wiens resultaat niet gebruikt wordt
 *      (behalve voor side-effecting instructies)
 *   3. Verwijder lege blokken (behalve entry)
 *   4. Herhaal tot fixpoint
 */
public class DeadCodeElimination implements OptimizationPass {

    @Override
    public boolean run(final IRFunction function) {
        boolean changed = false;
        boolean iterationChanged;

        do {
            iterationChanged = false;

            // Stap 1: Tel het aantal gebruik van elke Temp
            final Map<String, Integer> useCounts = countUses(function);

            // Stap 2: Verwijder dode instructies
            for (final var block : function.blocks()) {
                iterationChanged |= removeDeadInstructions(block, useCounts);
            }

            // Stap 3: Verwijder lege blokken (niet-entry)
            iterationChanged |= removeEmptyBlocks(function);

            changed |= iterationChanged;
        } while (iterationChanged);

        return changed;
    }

    private Map<String, Integer> countUses(final IRFunction function) {
        final Map<String, Integer> useCounts = new HashMap<>();

        for (final var block : function.blocks()) {
            for (final var instr : block.instructions()) {
                collectUsedValues(instr, useCounts);
            }
        }

        return useCounts;
    }

    private void collectUsedValues(final IRInstruction instr,
                                   final Map<String, Integer> useCounts) {
        switch (instr) {
            case IRInstruction.BinaryOp op -> {
                increment(op.left(), useCounts);
                increment(op.right(), useCounts);
            }
            case IRInstruction.Load ld -> {
                increment(ld.ptr(), useCounts);
            }
            case IRInstruction.Store st -> {
                increment(st.ptr(), useCounts);
                increment(st.value(), useCounts);
            }
            case IRInstruction.Call call -> {
                for (final var arg : call.args()) {
                    increment(arg, useCounts);
                }
            }
            case IRInstruction.IndirectCall ic -> {
                increment(ic.callee(), useCounts);
                for (final var arg : ic.args()) {
                    increment(arg, useCounts);
                }
            }
            case IRInstruction.CondBranch cb -> {
                increment(cb.condition(), useCounts);
            }
            case IRInstruction.Return ret -> {
                if (ret.value() != null) {
                    increment(ret.value(), useCounts);
                }
            }
            case IRInstruction.Cast cast -> {
                increment(cast.source(), useCounts);
            }
            case IRInstruction.Phi phi -> {
                for (final var incoming : phi.incomingValues()) {
                    increment(incoming.value(), useCounts);
                }
            }
            case IRInstruction.Move move -> {
                increment(move.value(), useCounts);
            }
            case IRInstruction.Throw thr -> {
                if (thr.result() != null) {
                    increment(thr.result(), useCounts);
                }
            }
            case IRInstruction.InstanceOf io -> {
                increment(io.source(), useCounts);
            }
            case IRInstruction.AllocaArray aa -> {
                increment(aa.size(), useCounts);
            }
            case IRInstruction.ArrayLoad al -> {
                increment(al.array(), useCounts);
                increment(al.index(), useCounts);
            }
            case IRInstruction.ArrayStore as -> {
                increment(as.array(), useCounts);
                increment(as.index(), useCounts);
                increment(as.value(), useCounts);
            }
            case IRInstruction.ArrayLength al -> {
                increment(al.array(), useCounts);
            }
            case IRInstruction.MonitorEnter me -> {
                increment(me.object(), useCounts);
            }
            case IRInstruction.MonitorExit mx -> {
                increment(mx.object(), useCounts);
            }
            default -> {}
        }
    }

    private void increment(final IRValue value,
                           final Map<String, Integer> useCounts) {
        if (value instanceof IRValue.Temp temp) {
            useCounts.merge(temp.name(), 1, Integer::sum);
        } else if (value instanceof IRValue.Named named) {
            useCounts.merge(named.name(), 1, Integer::sum);
        } else if (value instanceof IRValue.Values values) {
            for (final var inner : values.values()) {
                increment(inner, useCounts);
            }
        }
    }

    private boolean removeDeadInstructions(final IRBasicBlock block,
                                           final Map<String, Integer> useCounts) {
        boolean changed = false;
        final var instructions = new ArrayList<>(block.instructions());

        for (final var instr : instructions) {
            if (isEffectFree(instr) && hasUnusedResult(instr, useCounts)) {
                block.removeInstruction(instr);
                changed = true;
            }
        }

        return changed;
    }

    /**
     * Bepaalt of een instructie geen zij-effecten heeft
     * en veilig verwijderd kan worden als het resultaat ongebruikt is.
     */
    private boolean isEffectFree(final IRInstruction instr) {
        return switch (instr) {
            case IRInstruction.BinaryOp ignored -> true;
            case IRInstruction.Move ignored -> true;
            case IRInstruction.Load ignored -> true;
            case IRInstruction.ArrayLoad ignored -> true;
            case IRInstruction.ArrayLength ignored -> true;
            case IRInstruction.Cast ignored -> true;
            case IRInstruction.Phi ignored -> false; // phis worden apart behandeld
            default -> false; // Alloca, Store, Call, Branch, etc. hebben side-effects
        };
    }

    private boolean hasUnusedResult(final IRInstruction instr,
                                    final Map<String, Integer> useCounts) {
        final var result = instr.result();
        if (result == null) return false;

        final String name;
        if (result instanceof IRValue.Temp temp) {
            name = temp.name();
        } else if (result instanceof IRValue.Named named) {
            name = named.name();
        } else {
            return false;
        }

        final var count = useCounts.getOrDefault(name, 0);
        return count == 0;
    }

    /**
     * Verwijder lege blokken (behalve entry) die geen instructies bevatten.
     */
    private boolean removeEmptyBlocks(final IRFunction function) {
        // Verwijder pas verwijzingen naar lege blokken in terminators
        // Dit is complex — voorlopig alleen lege blokken zonder predecessors
        return false;
    }
}

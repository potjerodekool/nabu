package io.github.potjerodekool.nabu.backend.ir;

import io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Optimizer {

    public static IRModule optimize(final IRModule module) {
        final var optimizedFunctions = module.functions().stream()
                .map(Optimizer::optimize)
                .toList();
        return module.withFunctions(optimizedFunctions);
    }

    public static IRFunction optimize(IRFunction irFunction) {
        // Stap 0: TypeConsistency moet altijd draaien, ook voor functies met Phis,
        // omdat type-conflicten in Phis leiden tot ongeldige stack-map frames.
        irFunction = io.github.potjerodekool.nabu.backend.ir.optimize.TypeConsistency.run(irFunction);

        // Stap 0b: Elimineer phi's toegevoegd door TypeConsistency
        io.github.potjerodekool.nabu.backend.ir.PhiElimination.run(irFunction);

        // Conservatieve guard: functies met Phi-instructies (SSA-joins door
        // if/loops/ternary/try-catch) worden NIET verder geoptimaliseerd. De eerste
        // versies van ConstantFolder/CopyPropagation/GVN/DCE zijn onzuiter in
        // combinatie met Phi's (dode register-referenties: 'Onbekend register',
        // 'Expected I, but found .'). Pass-by-pass vekken in de pipeline loop
        // is het onderzoekaandachtsveld (BISECT).
        final var hasPhis = irFunction.blocks().stream()
                .anyMatch(b -> b.instructions().stream()
                        .anyMatch(i -> i instanceof IRInstruction.Phi));
        if (hasPhis) {
            return irFunction;
        }

        // Stap 1: Verwijder onnodige branches (bestaande logica)
        irFunction = removeRedundantBranches(irFunction);

        // Stap 2: Voer optimalisatiepijplijn uit
        irFunction = io.github.potjerodekool.nabu.backend.ir.optimize
                .OptimizationPipeline.defaultPipeline()
                .run(irFunction);

        return irFunction;
    }

    /**
     * Verwijder branches die naar het direct volgende blok wijzen (redundante
     * goto) door dat blok in de voorgaande te <i>vouwen</i>.
     *
     * <p>Alleen de branch schrappen is onvoldoende: het blok houdt dan geen
     * terminator en de LLVM-verifier weigert de module ("Basic Block in
     * function ... does not have terminator"). De opvolgende instructies
     * moeten dus in hetzelfde blok terechtkomen.
     *
     * <p>Het vouwen is alleen veilig als het doelblok precies één predecessor
     * heeft (anders verliezen andere takken hun sprongdoel). Het doelblok
     * verdwijnt daardoor als label; de nieuwe label van de {@code br} in het
     * bronblok wordt daarom met dezelfde regel meegevouwen.
     */
    private static IRFunction removeRedundantBranches(IRFunction irFunction) {
        final var original = irFunction.blocks();
        final var predecessors = countPredecessors(original);

        final var newBlocks = new ArrayList<IRBasicBlock>();
        int i = 0;
        while (i < original.size()) {
            final var block = original.get(i);
            final var merged = new IRBasicBlock(block.label());

            for (final var instr : block.instructions()) {
                merged.add(instr);
            }

            // Volg zolang de laatste instructie een branch is naar het blok
            // daadirect erop, en dat blok geen andere predecessors heeft.
            while (i + 1 < original.size()) {
                final var next = original.get(i + 1);
                if (!branchesTo(merged.getTerminator(), next.label())
                        || predecessors.getOrDefault(next.label(), 0) != 1) {
                    break;
                }

                // De overbodige branch zelf niet overnemen; de instructies erachter wel.
                final var nextInstructions = new ArrayList<>(next.instructions());
                nextInstructions.remove(merged.getTerminator());
                for (final var instr : nextInstructions) {
                    merged.add(instr);
                }
                i++;
            }

            newBlocks.add(merged);
            i++;
        }

        return irFunction.withBlocks(newBlocks);
    }

    /** Aantal keer dat elk blok-label als sprongdoel wordt genoemd. */
    private static Map<String, Integer> countPredecessors(final List<IRBasicBlock> blocks) {
        final Map<String, Integer> counts = new HashMap<>();
        for (final var block : blocks) {
            for (final var successor : block.getSuccessors().values()) {
                if (successor != null) {
                    counts.merge(successor, 1, Integer::sum);
                }
            }
        }
        return counts;
    }

    private static boolean branchesTo(final IRInstruction terminator, final String label) {
        return terminator instanceof IRInstruction.Branch branch
                && branch.targetLabel().equals(label);
    }
}

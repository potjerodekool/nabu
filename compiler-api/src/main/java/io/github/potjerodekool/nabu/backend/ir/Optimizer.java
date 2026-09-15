package io.github.potjerodekool.nabu.backend.ir;

import io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction;

import java.util.ArrayList;
import java.util.List;

public class Optimizer {

    public static IRModule optimize(final IRModule module) {
        final var optimizedFunctions = module.functions().stream()
                .map(Optimizer::optimize)
                .toList();
        return module.withFunctions(optimizedFunctions);
    }

    public static IRFunction optimize(IRFunction irFunction) {
        // Conservatieve guard: functies met Phi-instructies (SSA-joins door
        // if/loops/ternary/try-catch) worden NIET geoptimaliseerd. De eerste
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
     * Verwijder branches die naar het volgende blok wijzen (redundante goto).
     */
    private static IRFunction removeRedundantBranches(IRFunction irFunction) {
        final var newBlocks = new ArrayList<IRBasicBlock>();

        String previousLabel = null;
        IRInstruction lastInstruction = null;
        List<IRInstruction> newInstructions = new ArrayList<>();

        for (final var block : irFunction.blocks()) {
            if (!newInstructions.isEmpty()) {
                // Verwijder alleen een redundante branch als het blok meer bevat
                // dan alleen die branch. Anders wordt het blok leeg en mist het
                // zijn terminator (bv. een entry-blok dat alleen uit
                // 'br %next' bestaat), wat resulteert in ongeldige LLVM IR.
                if (shouldRemoveInstruction(lastInstruction, block.label())
                        && newInstructions.size() > 1) {
                    newInstructions.remove(lastInstruction);
                }
                final var newBlock = new IRBasicBlock(previousLabel, newInstructions);
                newBlocks.add(newBlock);
                newInstructions.clear();
            }

            newInstructions.addAll(block.instructions());

            if (block.instructions().isEmpty()) {
                lastInstruction = null;
            } else {
                lastInstruction = block.instructions().getLast();
            }

            previousLabel = block.label();
        }

        if (!newInstructions.isEmpty()) {
            final var newBlock = new IRBasicBlock(previousLabel, newInstructions);
            newBlocks.add(newBlock);
        }

        return irFunction.withBlocks(newBlocks);
    }

    private static boolean shouldRemoveInstruction(final IRInstruction instruction, final String label) {
        if (instruction instanceof IRInstruction.Branch branch) {
            return branch.targetLabel().equals(label);
        }

        return false;
    }
}

package io.github.potjerodekool.nabu.compiler.backend.ir2;

import io.github.potjerodekool.nabu.ir.IRBasicBlock;
import io.github.potjerodekool.nabu.ir.IRFunction;
import io.github.potjerodekool.nabu.ir.IRModule;
import io.github.potjerodekool.nabu.ir.instructions.IRInstruction;

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
        final var newBlocks = new ArrayList<IRBasicBlock>();

        String previousLabel = null;
        IRInstruction lastInstruction = null;
        List<IRInstruction> newInstructions = new ArrayList<>();

        for (final var block : irFunction.blocks()) {
            if (!newInstructions.isEmpty()) {
                if (shouldRemoveInstruction(lastInstruction, block.label())) {
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

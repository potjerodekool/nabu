package io.github.potjerodekool.nabu.compiler.ir;

import io.github.potjerodekool.nabu.compiler.ir.instructions.IRInstruction;

import java.util.*;

public class IRBasicBlock {

    private final String                  label;
    private final List<IRInstruction>     instructions = new ArrayList<>();
    private Map<String, String> successors;

    public IRBasicBlock(String label) {
        this.label = label;
    }

    public IRBasicBlock(final String label, final List<IRInstruction> instructions) {
        this.label = label;
        this.instructions.addAll(instructions);
        this.successors = new HashMap<>();
    }

    public String label() { return label; }

    public List<IRInstruction> instructions() {
        return Collections.unmodifiableList(instructions);
    }

    public void add(IRInstruction instr) {
        if (isTerminated())
            throw new IllegalStateException(
                "Kan geen instructie toevoegen aan beëindigd blok '" + label + "'");
        instructions.add(instr);
    }

    public void add(int index, IRInstruction instr) {
        instructions.add(index, instr);
    }

    public boolean isTerminated() {
        if (instructions.isEmpty()) return false;
        IRInstruction last = instructions.getLast();
        return last instanceof IRInstruction.Branch
            || last instanceof IRInstruction.CondBranch
            || last instanceof IRInstruction.Return;
    }

    public void setSuccessors(String trueLabel, String falseLabel) {
        successors.put("true", Objects.requireNonNull(trueLabel));
        successors.put("false", Objects.requireNonNull(falseLabel));
    }

    public void setSuccessor(String label) {
        successors.put("default", Objects.requireNonNull(label));
    }

    public Map<String, String> getSuccessors() {
        if (successors == null) {
            return Collections.emptyMap();
        }

        return Collections.unmodifiableMap(successors);
    }

    public String getSuccessor(String type) {
        return successors.get(type);
    }

    public IRInstruction getTerminator() {
        if (instructions.isEmpty()) {
            return null;
        }
        return instructions.getLast();
    }

    /**
     * Voeg een instructie toe vlak voor de terminator van dit blok.
     * Gebruikt voor phi-eliminatie: het invoegen van moves aan het einde
     * van predecessor-blokken.
     */
    public void insertBeforeTerminator(IRInstruction instr) {
        if (instructions.isEmpty() || !isTerminated()) {
            add(instr);
        } else {
            instructions.add(instructions.size() - 1, instr);
        }
    }

    /**
     * Verwijder een instructie uit dit blok (voor phi-eliminatie).
     */
    public boolean removeInstruction(IRInstruction instr) {
        return instructions.remove(instr);
    }

    /**
     * Vervang een instructie op een specifieke index.
     */
    public void setInstruction(int index, IRInstruction instr) {
        instructions.set(index, instr);
    }

    public BlockType getBlockType() {
        IRInstruction terminator = getTerminator();
        if (terminator instanceof IRInstruction.CondBranch) {
            return BlockType.CONDITIONAL;
        } else if (terminator instanceof IRInstruction.Branch) {
            return BlockType.UNCONDITIONAL;
        } else if (terminator instanceof IRInstruction.Return) {
            return BlockType.RETURN;
        } else {
            return BlockType.FALLTHROUGH;
        }
    }

    @Override
    public String toString() { return "%" + label; }

    public enum BlockType {
        CONDITIONAL,     // ends with if
        UNCONDITIONAL,   // ends with goto
        RETURN,          // ends with return
        FALLTHROUGH      // implicit fallthrough to next block
    }
}

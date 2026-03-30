package io.github.potjerodekool.nabu.ir;

import io.github.potjerodekool.nabu.ir.instructions.IRInstruction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class IRBasicBlock {

    private final String                  label;
    private final List<IRInstruction>     instructions = new ArrayList<>();

    public IRBasicBlock(String label) {
        this.label = label;
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

    public boolean isTerminated() {
        if (instructions.isEmpty()) return false;
        IRInstruction last = instructions.getLast();
        return last instanceof IRInstruction.Branch
            || last instanceof IRInstruction.CondBranch
            || last instanceof IRInstruction.Return;
    }

    @Override
    public String toString() { return "%" + label; }
}

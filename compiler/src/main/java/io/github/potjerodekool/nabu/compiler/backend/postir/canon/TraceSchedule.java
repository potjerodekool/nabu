package io.github.potjerodekool.nabu.compiler.backend.postir.canon;

import io.github.potjerodekool.nabu.compiler.backend.ir.statement.CJump;
import io.github.potjerodekool.nabu.compiler.backend.ir.statement.ILabelStatement;
import io.github.potjerodekool.nabu.compiler.backend.ir.statement.IStatement;
import io.github.potjerodekool.nabu.compiler.backend.ir.statement.Jump;
import io.github.potjerodekool.nabu.compiler.backend.ir.temp.ILabel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

public class TraceSchedule {

    private final List<IStatement> program = new ArrayList<>();

    private final ILabel endLabel;

    private final Map<ILabel, List<IStatement>> table = new HashMap<>();

    public TraceSchedule(final BasicBlocks bb) {
        final List<List<IStatement>> blocks = bb.getBlocks();
        this.endLabel = bb.getEndLabel();

        for (final List<IStatement> block : blocks) {
            table.put(getLabel(block), block);
        }

        for (final List<IStatement> block : blocks) {
            trace(getLabel(block));
        }

        getProgram().add(new ILabelStatement(endLabel));
    }

    private void trace(final ILabel startAt) {
        final List<IStatement> block = table.get(startAt);

        if (block != null) {
            table.remove(startAt);

            final int lastIndex = block.size() - 1;

            IntStream.range(0, lastIndex)
                    .forEach(index -> getProgram().add(block.get(index)));

            final IStatement last = block.get(lastIndex);

            if (last instanceof CJump cJump) {
                final ILabel falseTarget = cJump.getFalseLabel();
                final ILabel trueTarget = cJump.getTrueLabel();

                if (table.containsKey(trueTarget)) {
                    getProgram().add(cJump.flip());
                    trace(trueTarget);
                } else if (table.containsKey(falseTarget)) {
                    getProgram().add(cJump);
                    trace(falseTarget);
                } else {
                    ILabel newFalseLabel = ILabel.gen();
                    getProgram().add(cJump.changeFalseLabel(newFalseLabel));
                    getProgram().add(new ILabelStatement(newFalseLabel));
                    getProgram().add(new Jump(falseTarget));
                }
            } else {
                final List<ILabel> targets = last.getJumpTargets();

                if (targets.size() != 1) {
                    program.add(last);
                } else {
                    final ILabel target = targets.getFirst();
                    if (table.containsKey(target)) {
                        trace(target);
                    } else if (!(target == endLabel && table.isEmpty())) {
                        program.add(last);
                    }
                }
            }
        }
    }

    public List<IStatement> getProgram() {
        return program;
    }

    private ILabel getLabel(final List<IStatement> block) {
        return ((ILabelStatement) block.getFirst()).getLabel();
    }
}

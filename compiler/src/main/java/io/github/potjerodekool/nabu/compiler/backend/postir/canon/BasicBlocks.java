package io.github.potjerodekool.nabu.compiler.backend.postir.canon;

import io.github.potjerodekool.nabu.compiler.backend.ir.statement.ILabelStatement;
import io.github.potjerodekool.nabu.compiler.backend.ir.statement.IStatement;
import io.github.potjerodekool.nabu.compiler.backend.ir.statement.IThrowStatement;
import io.github.potjerodekool.nabu.compiler.backend.ir.statement.Jump;
import io.github.potjerodekool.nabu.compiler.backend.ir.temp.ILabel;

import java.util.ArrayList;
import java.util.List;

public class BasicBlocks {

    private List<IStatement> currentBlock = null;

    private final List<List<IStatement>> blocks = new ArrayList<>();

    private final ILabel endLabel;

    public BasicBlocks(final List<IStatement> statements) {
        final var lastStatement = statements.getLast();

        if (lastStatement instanceof ILabelStatement labelStatement) {
            endLabel = labelStatement.getLabel();
        } else {
            endLabel = new ILabel("basicEND");
        }

        mkBlocks(statements);
    }

    private void mkBlocks(final List<IStatement> statements) {
        final List<IStatement> list;

        if (statements.getFirst() instanceof ILabelStatement) {
            list = statements;
        } else {
            list = new ArrayList<>();
            list.add(new ILabelStatement(new ILabel()));
            list.addAll(statements);
        }

        boolean seenEndLabel = false;

        for (final var statement : list) {
            if (statement.isJump()) {
                currentBlock.add(statement);

                if (statement instanceof IThrowStatement throwStatement) {
                    throwStatement.setJumpTarget(endLabel);
                }

                endCurrentBlock();
            } else if (statement instanceof ILabelStatement labelStatement) {
                if (labelStatement.getLabel() == endLabel) {
                    seenEndLabel = true;
                }

                startNewBlock((ILabelStatement) statement);
            } else {
                currentBlock.add(statement);
            }
        }

        if (currentBlock != null) {
            if (!seenEndLabel) {
                currentBlock.add(new Jump(endLabel));
                currentBlock.add(new ILabelStatement(endLabel));
            }
            endCurrentBlock();
        }
    }

    private void endCurrentBlock() {
        if (currentBlock != null) {
            blocks.add(currentBlock);
        }
        currentBlock = null;
    }

    private void startNewBlock(final ILabelStatement startLabel) {
        if (currentBlock != null) {
            //currentBlock.add(new Jump(startLabel.getLabel()));
            endCurrentBlock();
        }

        currentBlock = new ArrayList<>();
        currentBlock.add(startLabel);
    }

    public List<List<IStatement>> getBlocks() {
        return blocks;
    }

    ILabel getEndLabel() {
        return endLabel;
    }

}

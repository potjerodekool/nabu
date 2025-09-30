package io.github.potjerodekool.nabu.compiler.backend.postir.canon;

import io.github.potjerodekool.nabu.compiler.backend.graph.IRFlowGraphBuilder;
import io.github.potjerodekool.nabu.compiler.backend.graph.Node;
import io.github.potjerodekool.nabu.compiler.backend.ir.Frame;
import io.github.potjerodekool.nabu.compiler.backend.ir.ProcFrag;
import io.github.potjerodekool.nabu.compiler.backend.ir.expression.TempExpr;
import io.github.potjerodekool.nabu.compiler.backend.ir.statement.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class IrCleaner {

    private IrCleaner() {
    }

    public static ProcFrag cleanUp(final ProcFrag procFrag) {
        var frag = canonizeProcFrag(procFrag);
        frag = basicBlocks(frag);
        doCleanUp(frag);
        return frag;
    }

    public static ProcFrag insertReturnIfNeeded(final ProcFrag procFrag) {
        final var body = procFrag.getBody();
        final var flowGraph = IRFlowGraphBuilder.build(
                body
        );

        final var newStatements = new ArrayList<IStatement>();

        for (final IStatement statement : body) {
            newStatements.add(statement);

            if (statement instanceof ILabelStatement) {
                final var node = flowGraph.getRevMap().get(statement);
                if (node.succ().isEmpty()) {
                    final var move = new Move(
                            new TempExpr(-1, null),
                            new TempExpr(Frame.V0)
                    );

                    newStatements.add(move);
                    newStatements.add(new ILabelStatement());
                }
            }
        }

        return new ProcFrag(newStatements);
    }

    static void doCleanUp(final ProcFrag frag) {
        final var flowGraph = IRFlowGraphBuilder.build(
                frag.getBody()
        );

        final var statements = frag.getBody();
        final var lastLabelStatement = getLastLabelStatement(statements);

        final var statementIterator = statements.iterator();
        final var revMap = flowGraph.getRevMap();

        if (statementIterator.hasNext()) {
            statementIterator.next();
        }

        final var removedNodes = new ArrayList<Node>();

        while (statementIterator.hasNext()) {
            final var statement = statementIterator.next();
            final var node = revMap.get(statement);

            if (node != null) {
                final var pred = new ArrayList<>(node.pred());
                pred.removeAll(removedNodes);

                if (pred.isEmpty() && !isLastLabel(statement, lastLabelStatement)) {
                    statementIterator.remove();
                    removedNodes.add(node);
                }
            }
        }

        if (!removedNodes.isEmpty()) {
            doCleanUp(frag);
        }
    }

    private static ILabelStatement getLastLabelStatement(final List<IStatement> statements) {
        final var lastStatement = statements.getLast();

        if (lastStatement instanceof ILabelStatement labelStatement) {
            return labelStatement;
        }

        return null;
    }

    private static boolean isLastLabel(final IStatement statement, final ILabelStatement lastLabelStatement) {
        return statement == lastLabelStatement;
    }

    public static ProcFrag canonizeProcFrag(final ProcFrag procFrag) {
        if (procFrag.getBody().isEmpty()) {
            return procFrag;
        } else {
            final var newBody = Canon.linearize(procFrag.getBody().getFirst());

            newBody.forEach(Objects::requireNonNull);

            return new ProcFrag(
                    newBody
            );
        }
    }

    public static ProcFrag basicBlocks(final ProcFrag procFrag) {
        if (procFrag.getBody().isEmpty()) {
            return procFrag;
        } else {
            final var basicBlocks = new BasicBlocks(procFrag.getBody());
            final var statements = new ArrayList<IStatement>();

            basicBlocks.getBlocks().forEach(statements::addAll);
            return new ProcFrag(statements);
        }
    }
}
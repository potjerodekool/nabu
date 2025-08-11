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
         return procFrag;
        /*
        var body = procFrag.getBody();

        final var flowGraph = IRFlowGraphBuilder.build(
                body
        );

        final var lastNode = flowGraph.getLastNode();
        final var lastStatement = flowGraph.getMap().get(lastNode);

        final var addReturn = !(lastStatement instanceof Move move
                && move.getDst() instanceof TempExpr dest
                && dest.getTemp().getIndex() == Frame.RV);

        if (addReturn) {
            final var lastStatementIndex = body.indexOf(lastStatement);

            final var statements = new ArrayList<>(body);

            statements.add(lastStatementIndex + 1, new Move(
                    new TempExpr(-1, null),
                    new TempExpr(Frame.V0)
            ));

            return new ProcFrag(statements);
        } else {
            return procFrag;
        }
        */
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
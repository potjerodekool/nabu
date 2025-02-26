package io.github.potjerodekool.nabu.compiler.backend.graph;

import io.github.potjerodekool.nabu.compiler.backend.ir.statement.ILabelStatement;
import io.github.potjerodekool.nabu.compiler.backend.ir.statement.IStatement;
import io.github.potjerodekool.nabu.compiler.backend.ir.temp.ILabel;

import java.util.*;

public class IRFlowGraphBuilder2 {

    public static IRFlowGraph build(final IStatement statement) {
        return build(List.of(statement));
    }

    public static IRFlowGraph build(final List<IStatement> statements) {
        final IRFlowGraph flowGraph = new IRFlowGraph();
        final Map<ILabel, IStatement> labelInstrMap = new HashMap<>();

        statements.forEach(statement -> {
            final Node node = flowGraph.newNode();
            flowGraph.map.put(node, statement);
            flowGraph.revMap.put(statement,node);

            if (statement instanceof final ILabelStatement labelStatement) {
                labelInstrMap.put(labelStatement.getLabel(), statement);
            }
        });

        build(
                statements,
                flowGraph,
                labelInstrMap,
                new HashSet<>()
        );

        return flowGraph;
    }

    private static void build(final List<IStatement> statements,
                              final IRFlowGraph flowGraph,
                              final Map<ILabel, IStatement> labelInstrMap,
                              final Set<IStatement> processed) {
        Node previousNode = null;

        for (final var statement : statements) {
            process(
                    statement,
                    flowGraph,
                    labelInstrMap,
                    processed,
                    previousNode
            );
            previousNode = flowGraph.revMap.get(statement);
        }
    }

    private static void process(final IStatement statement,
                                final IRFlowGraph flowGraph,
                                final Map<ILabel, IStatement> labelInstrMap,
                                final Set<IStatement> processed,
                                final Node previousNode) {
        if (processed.contains(statement)) {
            return;
        }

        processed.add(statement);

        final var jumpTargets = statement.getJumpTargets();

        if (previousNode != null) {
            final var node = flowGraph.revMap.get(statement);
            flowGraph.addEdge(previousNode, node);
        }

        if (!jumpTargets.isEmpty()) {
            jumpTargets.forEach(target -> {
                final var from = flowGraph.revMap.get(statement);
                final var toStatement = labelInstrMap.get(target);
                final var to = flowGraph.revMap.get(toStatement);
                flowGraph.addEdge(from, to);
                process(
                        toStatement,
                        flowGraph,
                        labelInstrMap,
                        processed,
                        from
                );
            });
        }
    }
}

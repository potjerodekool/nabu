package io.github.potjerodekool.nabu.compiler.backend.graph;

import io.github.potjerodekool.nabu.compiler.backend.ir.Frame;
import io.github.potjerodekool.nabu.compiler.backend.ir.expression.TempExpr;
import io.github.potjerodekool.nabu.compiler.backend.ir.statement.ILabelStatement;
import io.github.potjerodekool.nabu.compiler.backend.ir.statement.IStatement;
import io.github.potjerodekool.nabu.compiler.backend.ir.statement.Move;
import io.github.potjerodekool.nabu.compiler.backend.ir.temp.ILabel;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class IRFlowGraphBuilder {

    private IRFlowGraphBuilder() {
    }

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

        for (int i = 0; i < statements.size(); i++) {
            final IStatement head = statements.get(i);

            if (head instanceof Move move) {
                if (move.getDst() instanceof TempExpr tempExpr) {
                    final int index = tempExpr.getTemp().getIndex();

                    if (index == Frame.RV) {
                        continue;
                    }
                }
            }

            final List<ILabel> jumps = head.getJumpTargets();

            if (jumps == null || jumps.isEmpty()) {
                // Instruction fall through
                final List<IStatement> tail = statements.subList(i + 1, statements.size());

                if (!tail.isEmpty()) {
                    flowGraph.addEdge(flowGraph.revMap.get(head),
                            flowGraph.revMap.get(tail.getFirst()));
                }
            } else {
                // branches
                jumps.forEach(label ->
                        flowGraph.addEdge(flowGraph.revMap.get(head),
                                flowGraph.revMap.get(labelInstrMap.get(label)))
                );
            }
        }

        return flowGraph;
    }

}

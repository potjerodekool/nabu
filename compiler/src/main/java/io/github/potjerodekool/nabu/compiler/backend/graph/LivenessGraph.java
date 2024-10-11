package io.github.potjerodekool.nabu.compiler.backend.graph;

import io.github.potjerodekool.nabu.compiler.backend.ir.temp.Temp;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LivenessGraph extends InterferenceGraph {

    private final Map<Node, Temp> ntMap = new HashMap<>();
    private final Map<Temp, Node> tnMap = new HashMap<>();

    public LivenessGraph(final LivenessAnaliser liveness) {
        final var flowGraph = liveness.getFlowGraph();

        flowGraph.getNodes().forEach(node -> {
            final List<Temp> temps = liveness.getIn().get(node.getKey());
            temps.forEach(temp -> {
                if (!tnMap.containsKey(temp)) {
                    final Node n = new Node(this);
                    tnMap.put(temp, n);
                    ntMap.put(n, temp);
                }
            });
        });

        flowGraph.getNodes().forEach(node -> {
            final List<Temp> temps = liveness.getOut().get(node.getKey());
            temps.forEach(temp -> {
                if (!tnMap.containsKey(temp)) {
                    final Node n = new Node(this);
                    tnMap.put(temp, n);
                    ntMap.put(n, temp);
                }
            });
        });

        flowGraph.getNodes().forEach(node -> {
            final var g = liveness.getFlowGraph();
            final Temp[] defs = liveness.getFlowGraph().def(node);

            if (defs != null) {
                final List<Temp> pList = liveness.getOut().get(node.getKey());
                if (!g.isMove(node)) {
                    for (final Temp def : defs) {
                        final Node from = tnMap.get(def);
                        if (from != null) {
                            pList.forEach(p -> this.addEdge(from, tnMap.get(p)));
                        }
                    }
                } else {
                    for (final Temp def : defs) {
                        pList.forEach(p -> {
                            final Temp[] use = g.use(node);
                            if (use.length > 1 && p != use[1]) {
                                this.addEdge(tnMap.get(def), tnMap.get(p));
                            }
                        });
                    }
                }
            }
        });
    }

    @Override
    public Node node(final Temp temp) {
        return null;
    }

    @Override
    public Temp temp(final Node node) {
        return null;
    }

}
